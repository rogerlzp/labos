package com.labos.kernel.workflow.runtime.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Single-host application service owning the {@code DEVICE_COMMAND} Outbox -> {@link DeviceCommandAdapter}
 * -> durable {@code inbox_receipt} -> {@code command_ledger} closed loop.
 *
 * <p>Safety invariants enforced on every call:
 * <ul>
 *   <li>{@code markCommandSendStarted} ALWAYS commits BEFORE {@link DeviceCommandAdapter#send} is invoked.</li>
 *   <li>Once a {@code command_id} crosses {@code SEND_STARTED}, it is NEVER blindly re-sent; any transport
 *       uncertainty or exception marks the Command {@code UNKNOWN} ({@code SIDE_EFFECT_UNKNOWN}) and
 *       dead-letters the outbox row until explicit external verification via {@link #reconcileUnknownCommand}.</li>
 *   <li>Every device result (whether synchronous or asynchronous) passes through durable
 *       {@code receiveInbox -> applyCommandEvent}, preserving idempotent duplicate replay and
 *       {@code inbox_protocol_violation} recording on conflicting payloads.</li>
 * </ul>
 */
public final class DeviceCommandExecutionService {
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum CommandDeliveryStatus {
        SENT_ASYNC,
        COMPLETED_SYNC,
        MARKED_UNKNOWN,
        SKIPPED_GATE_OR_STATE,
        DEAD_LETTERED_STALE
    }

    public record CommandDeliverySummary(
            Map<UUID, CommandDeliveryStatus> outcomes,
            int deadLetteredInvalid) {
        public CommandDeliverySummary {
            outcomes = Map.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
        }
    }

    public enum InboxStatus {
        APPLIED,
        DUPLICATE_REPLAY,
        PROTOCOL_VIOLATION
    }

    public record InboxIngestionOutcome(InboxStatus status, UUID inboxId) {
        public InboxIngestionOutcome {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(inboxId, "inboxId");
        }
    }

    private final WorkflowRuntimeRepository repository;
    private final DeviceCommandAdapter adapter;

    public DeviceCommandExecutionService(
            WorkflowRuntimeRepository repository,
            DeviceCommandAdapter adapter) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    /**
     * Claim up to {@code batchSize} {@code PENDING} {@code DEVICE_COMMAND} Outbox rows for {@code tenantId},
     * persist {@code SEND_STARTED} before adapter invocation, and handle sync/async/uncertain outcomes.
     */
    public CommandDeliverySummary deliverPendingCommands(
            UUID tenantId, int batchSize, String dispatcherId) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (dispatcherId == null || dispatcherId.isBlank())
            throw new IllegalArgumentException("dispatcherId is required");

        List<WorkflowRuntimeRepository.OutboxMessage> claimed =
                repository.beginOutboxSendForKind(tenantId, "DEVICE_COMMAND", batchSize);
        Map<UUID, CommandDeliveryStatus> outcomes = new LinkedHashMap<>();
        int deadLetteredInvalid = 0;

        for (WorkflowRuntimeRepository.OutboxMessage msg : claimed) {
            UUID commandId = extractUuidField(msg.payloadJson(), "commandId");
            if (commandId == null) {
                repository.markOutboxDeadLetter(tenantId, msg.outboxId());
                deadLetteredInvalid++;
                continue;
            }
            Optional<WorkflowRuntimeRepository.CommandRecord> loaded = repository.loadCommand(tenantId, commandId);
            if (loaded.isEmpty()) {
                repository.markOutboxDeadLetter(tenantId, msg.outboxId());
                outcomes.put(commandId, CommandDeliveryStatus.DEAD_LETTERED_STALE);
                continue;
            }
            WorkflowRuntimeRepository.CommandRecord cmd = loaded.get();
            if (!"CREATED".equals(cmd.commandState()) && !"DISPATCH_PENDING".equals(cmd.commandState())) {
                // Command was already cancelled, sent, or terminalized: NEVER re-send.
                repository.markOutboxDeadLetter(tenantId, msg.outboxId());
                outcomes.put(commandId, CommandDeliveryStatus.DEAD_LETTERED_STALE);
                continue;
            }

            UUID dispatchId = UUID.randomUUID();
            boolean sendStarted;
            try {
                sendStarted = repository.markCommandSendStarted(
                        tenantId, commandId, cmd.version(), msg.outboxId(),
                        dispatchId, 1, dispatcherId);
            } catch (WorkflowRuntimeRepository.InvalidTransitionException alreadySent) {
                repository.markOutboxDeadLetter(tenantId, msg.outboxId());
                outcomes.put(commandId, CommandDeliveryStatus.DEAD_LETTERED_STALE);
                continue;
            }
            if (!sendStarted) {
                // Pre-send gate closed or Attempt no longer STARTED: SEND_STARTED was NOT crossed.
                Optional<WorkflowRuntimeRepository.DispatchedAttemptRecord> attempt =
                        repository.loadDispatchedAttempt(tenantId, cmd.attemptId());
                if (attempt.isEmpty() || !"STARTED".equals(attempt.get().attemptState())) {
                    repository.markOutboxDeadLetter(tenantId, msg.outboxId());
                } else {
                    repository.requeueOutboxToPending(tenantId, msg.outboxId());
                }
                outcomes.put(commandId, CommandDeliveryStatus.SKIPPED_GATE_OR_STATE);
                continue;
            }

            // SEND_STARTED + SIDE_EFFECT_STARTED(command:<id>) are now durably committed.
            DeviceCommandAdapter.DeviceCommandEnvelope envelope = new DeviceCommandAdapter.DeviceCommandEnvelope(
                    tenantId, cmd.runId(), cmd.attemptId(), commandId, dispatchId,
                    cmd.deviceId(), cmd.commandKind(), cmd.idempotencyClass(), cmd.payloadJson());
            DeviceCommandAdapter.SendResult result;
            try {
                result = Objects.requireNonNull(adapter.send(envelope), "DeviceCommandAdapter returned null SendResult");
            } catch (Throwable transportFailure) {
                CommandDeliveryStatus status = handlePostSendUncertainty(tenantId, commandId, msg.outboxId(),
                        transportFailure.getClass().getSimpleName() + ": " + safeMessage(transportFailure));
                outcomes.put(commandId, status);
                continue;
            }

            switch (result.status()) {
                case ACCEPTED_ASYNC -> {
                    repository.markOutboxDelivered(tenantId, msg.outboxId());
                    outcomes.put(commandId, CommandDeliveryStatus.SENT_ASYNC);
                }
                case COMPLETED_IMMEDIATELY -> {
                    // Mark DEVICE_COMMAND outbox DELIVERED BEFORE applying SUCCEEDED Inbox event so
                    // downstream completeAttempt/maybeCompleteGraphRun never sees a stale SENDING row.
                    repository.markOutboxDelivered(tenantId, msg.outboxId());
                    ingestDeviceEvent(
                            tenantId, cmd.runId(), cmd.deviceId(), result.sourceMessageId(),
                            "DEVICE_COMMAND_RESULT", commandId, "SUCCEEDED",
                            result.deviceSequence(), result.outcomeJson());
                    outcomes.put(commandId, CommandDeliveryStatus.COMPLETED_SYNC);
                }
                case DELIVERY_UNCERTAIN -> {
                    CommandDeliveryStatus status = handlePostSendUncertainty(tenantId, commandId, msg.outboxId(),
                            result.errorMessage() == null ? "adapter-delivery-uncertain" : result.errorMessage());
                    outcomes.put(commandId, status);
                }
            }
        }
        return new CommandDeliverySummary(outcomes, deadLetteredInvalid);
    }

    /**
     * Durable Inbox ingestion for device command events: records {@code inbox_receipt}, detects
     * duplicate replay vs {@code inbox_protocol_violation}, and atomically applies {@code command_ledger}
     * + {@code worker_execution_fact} transitions.
     */
    public InboxIngestionOutcome ingestDeviceEvent(
            UUID tenantId, UUID runId, String source, String sourceMessageId,
            String messageType, UUID commandId, String targetState,
            String deviceSequence, String outcomeJson) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(commandId, "commandId");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
        if (sourceMessageId == null || sourceMessageId.isBlank())
            throw new IllegalArgumentException("sourceMessageId is required");
        if (messageType == null || messageType.isBlank())
            throw new IllegalArgumentException("messageType is required");
        if (targetState == null || targetState.isBlank())
            throw new IllegalArgumentException("targetState is required");

        Optional<WorkflowRuntimeRepository.CommandRecord> command = repository.loadCommand(tenantId, commandId);
        if (command.isEmpty() || !runId.equals(command.get().runId())) {
            throw new WorkflowRuntimeRepository.InvalidTransitionException(
                    "Command does not belong to target Run: " + commandId);
        }
        if (!command.get().deviceId().equals(source)) {
            throw new WorkflowRuntimeRepository.InvalidTransitionException(
                    "Device event source mismatch: expected " + command.get().deviceId() + ", got " + source);
        }

        String normalizedOutcome = (outcomeJson == null || outcomeJson.isBlank()) ? "{}" : outcomeJson;
        String inboxPayload = "{\"commandId\":\"" + commandId
                + "\",\"deviceId\":\"" + jsonEscape(source)
                + "\",\"state\":\"" + jsonEscape(targetState)
                + "\",\"deviceSequence\":" + (deviceSequence == null ? "null" : "\"" + jsonEscape(deviceSequence) + "\"")
                + ",\"outcome\":" + normalizedOutcome + "}";

        WorkflowRuntimeRepository.InboxReceipt receipt = repository.receiveInbox(
                tenantId, source, sourceMessageId, messageType, runId, inboxPayload);
        if (receipt.payloadConflict()) {
            return new InboxIngestionOutcome(InboxStatus.PROTOCOL_VIOLATION, receipt.inboxId());
        }
        if (!receipt.inserted()) {
            Optional<String> state = repository.findInboxReceiptState(tenantId, receipt.inboxId());
            if (state.isPresent() && "PROCESSED".equals(state.get())) {
                return new InboxIngestionOutcome(InboxStatus.DUPLICATE_REPLAY, receipt.inboxId());
            }
        }
        repository.applyCommandEvent(
                tenantId, runId, receipt.inboxId(), commandId,
                targetState, deviceSequence, normalizedOutcome);
        return new InboxIngestionOutcome(InboxStatus.APPLIED, receipt.inboxId());
    }

    /**
     * T21 explicit external reconciliation for an {@code UNKNOWN} / {@code DELIVERY_UNKNOWN} Command.
     */
    public boolean reconcileUnknownCommand(
            UUID tenantId, UUID commandId, String resolvedState,
            String deviceSequence, String evidenceJson, String actor) {
        return repository.resolveUnknownCommand(
                tenantId, commandId, resolvedState, deviceSequence,
                (evidenceJson == null || evidenceJson.isBlank()) ? "{}" : evidenceJson,
                actor);
    }

    /**
     * Handle post-{@code SEND_STARTED} uncertainty: {@code markCommandUnknown} only transitions
     * non-terminal states ({@code SEND_STARTED/ACKNOWLEDGED/RUNNING/DELIVERY_UNKNOWN -> UNKNOWN})
     * and never overwrites a concurrent {@code SUCCEEDED/FAILED/CANCELLED} Inbox outcome.
     *
     * <p>Marking the {@code DEVICE_COMMAND} outbox {@code DEAD_LETTER} ONLY halts automatic
     * re-dispatch so the physical action is never blindly repeated; it does not assert command
     * failure and preserves {@code command_ledger='UNKNOWN'} for T21 {@link #reconcileUnknownCommand}.
     */
    private CommandDeliveryStatus handlePostSendUncertainty(
            UUID tenantId, UUID commandId, UUID outboxId, String reason) {
        String evidenceJson = "{\"reason\":\"" + jsonEscape(reason) + "\"}";
        repository.markCommandUnknown(tenantId, commandId, evidenceJson);
        Optional<WorkflowRuntimeRepository.CommandRecord> current = repository.loadCommand(tenantId, commandId);
        if (current.isPresent() && "SUCCEEDED".equals(current.get().commandState())) {
            repository.markOutboxDelivered(tenantId, outboxId);
            return CommandDeliveryStatus.COMPLETED_SYNC;
        }
        repository.markOutboxDeadLetter(tenantId, outboxId);
        return CommandDeliveryStatus.MARKED_UNKNOWN;
    }

    private static UUID extractUuidField(String json, String field) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode tree = JSON.readTree(json);
            JsonNode node = tree.get(field);
            if (node == null || !node.isTextual() || node.asText().isBlank()) return null;
            return UUID.fromString(node.asText());
        } catch (Exception invalid) {
            return null;
        }
    }

    private static String safeMessage(Throwable t) {
        return t == null || t.getMessage() == null ? "" : t.getMessage();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
