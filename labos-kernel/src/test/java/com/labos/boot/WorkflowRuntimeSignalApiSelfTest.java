package com.labos.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.boot.web.ApiAuthInterceptor;
import com.labos.boot.web.AuditRecorder;
import com.labos.boot.web.controller.WorkflowRuntimeController;
import com.labos.kernel.api.AuthService;
import com.labos.kernel.persistence.GraphMapper;
import com.labos.kernel.workflow.runtime.coordinator.WaitOutcomes;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.method.HandlerMethod;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-logic unit tests for the G5 HTTP Wait Signal API
 * ({@code POST /api/runtime/runs/{runId}/signals}): RBAC enforcement via
 * {@link ApiAuthInterceptor}, strict request validation, JSON payload canonicalization,
 * and REST status mapping across all {@link WaitOutcomes}. Does not connect to PostgreSQL.
 */
public final class WorkflowRuntimeSignalApiSelfTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger PASSED = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:1/not-used", "unused", "unused");
        PlatformTransactionManager tm = new DataSourceTransactionManager(ds);
        GraphMapper mapper = (GraphMapper) Proxy.newProxyInstance(
                GraphMapper.class.getClassLoader(), new Class<?>[]{GraphMapper.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("Mapper must not be called in pure signal API test: " + method.getName());
                });

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("dataSource", DataSource.class, () -> ds);
            ctx.registerBean("transactionManager", PlatformTransactionManager.class, () -> tm);
            ctx.registerBean("graphMapper", GraphMapper.class, () -> mapper);
            ctx.register(WorkflowRuntimeKernelConfiguration.class);
            ctx.refresh();

            WorkflowRuntimeKernelFacade facade = ctx.getBean(WorkflowRuntimeKernelFacade.class);
            WorkflowRuntimeController controller = new WorkflowRuntimeController(facade, new AuditRecorder(null));

            verifyRbacEnforcement(controller);
            verifyRequestValidation(controller);
            verifyPayloadCanonicalization();
            verifyOutcomeHttpMapping();
        }

        System.out.println("Workflow Runtime HTTP Wait Signal API：通过 " + PASSED.get() + ", 失败 0");
    }

    private static void verifyRbacEnforcement(WorkflowRuntimeController controller) throws Exception {
        String runId = UUID.randomUUID().toString();
        String signalPath = "/api/runtime/runs/" + runId + "/signals";
        String getRunPath = "/api/runtime/runs/" + runId;
        HandlerMethod signalHandler = new HandlerMethod(controller,
                WorkflowRuntimeController.class.getMethod(
                        "receiveWaitSignal", String.class, String.class, HttpServletRequest.class));
        HandlerMethod getRunHandler = new HandlerMethod(controller,
                WorkflowRuntimeController.class.getMethod("getRun", String.class));

        require(guard(signalPath, "POST", null, null, signalHandler) == 401,
                "POST /api/runtime/runs/{id}/signals without auth must return 401");
        require(guard(signalPath, "POST", "viewer", "Bearer invalid-token", signalHandler) == 401,
                "POST /api/runtime/runs/{id}/signals with invalid token must return 401");
        require(guard(signalPath, "POST", "viewer", "Bearer tok", signalHandler) == 403,
                "POST /api/runtime/runs/{id}/signals must forbid viewer (403)");
        require(guard(signalPath, "POST", "designer", "Bearer tok", signalHandler) == 403,
                "POST /api/runtime/runs/{id}/signals must forbid designer (403)");
        require(guard(signalPath, "POST", "operator", "Bearer tok", signalHandler) == 200,
                "POST /api/runtime/runs/{id}/signals must allow operator (200)");
        require(guard(signalPath, "POST", "admin", "Bearer tok", signalHandler) == 200,
                "POST /api/runtime/runs/{id}/signals must allow admin (200)");
        require(guard(getRunPath, "GET", "viewer", "Bearer tok", getRunHandler) == 200,
                "GET /api/runtime/runs/{id} must allow authenticated viewer (200)");
    }

    private static void verifyRequestValidation(WorkflowRuntimeController controller) {
        String validRunId = UUID.randomUUID().toString();

        require(controller.receiveWaitSignal("not-a-uuid",
                "{\"sourceMessageId\":\"m1\",\"correlationKey\":\"k1\"}", null).getStatusCode().value() == 400,
                "Invalid runId UUID must return 400");

        for (String badBody : new String[]{
                null,
                "",
                "   ",
                "{bad-json",
                "[1,2,3]",
                "{\"correlationKey\":\"qc.ok\"}",
                "{\"sourceMessageId\":null,\"correlationKey\":\"qc.ok\"}",
                "{\"sourceMessageId\":\"\",\"correlationKey\":\"qc.ok\"}",
                "{\"sourceMessageId\":\"   \",\"correlationKey\":\"qc.ok\"}",
                "{\"sourceMessageId\":123,\"correlationKey\":\"qc.ok\"}",
                "{\"sourceMessageId\":\"m1\"}",
                "{\"sourceMessageId\":\"m1\",\"correlationKey\":null}",
                "{\"sourceMessageId\":\"m1\",\"correlationKey\":\"\"}",
                "{\"sourceMessageId\":\"m1\",\"correlationKey\":\"  \"}",
                "{\"sourceMessageId\":\"m1\",\"correlationKey\":99}",
                "{\"source\":\"\",\"sourceMessageId\":\"m1\",\"correlationKey\":\"qc.ok\"}",
                "{\"source\":\"   \",\"sourceMessageId\":\"m1\",\"correlationKey\":\"qc.ok\"}",
                "{\"source\":42,\"sourceMessageId\":\"m1\",\"correlationKey\":\"qc.ok\"}",
                "{\"sourceMessageId\":\"m1\",\"correlationKey\":\"qc.ok\",\"payload\":\"text\"}",
                "{\"sourceMessageId\":\"m1\",\"correlationKey\":\"qc.ok\",\"payload\":[1,2]}",
                "{\"sourceMessageId\":\"m1\",\"correlationKey\":\"qc.ok\",\"payload\":42}"
        }) {
            ResponseEntity<String> resp = controller.receiveWaitSignal(validRunId, badBody, null);
            require(resp.getStatusCode().value() == 400,
                    "Expected 400 validation failure for body=" + badBody + ", got "
                            + resp.getStatusCode().value() + " body=" + resp.getBody());
        }
    }

    private static void verifyPayloadCanonicalization() throws Exception {
        String c1 = WorkflowRuntimeController.canonicalizePayloadJson(
                JSON.readTree("{\"b\":2,\"a\":{\"y\":1,\"x\":0},\"arr\":[{\"k2\":2,\"k1\":1},2]}"));
        String c2 = WorkflowRuntimeController.canonicalizePayloadJson(
                JSON.readTree("{\"arr\":[{\"k1\":1,\"k2\":2},2],\"a\":{\"x\":0,\"y\":1},\"b\":2}"));
        require(c1.equals(c2), "Reordered object keys (including inside arrays) must canonicalize identically: "
                + c1 + " vs " + c2);

        String a1 = WorkflowRuntimeController.canonicalizePayloadJson(JSON.readTree("{\"arr\":[1,2]}"));
        String a2 = WorkflowRuntimeController.canonicalizePayloadJson(JSON.readTree("{\"arr\":[2,1]}"));
        require(!a1.equals(a2), "Array element order must be preserved during canonicalization");

        for (String nonObj : List.of("[1,2]", "\"text\"", "42", "null")) {
            boolean rejected = false;
            try {
                WorkflowRuntimeController.canonicalizePayloadJson(JSON.readTree(nonObj));
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            require(rejected, "Non-object payload must be rejected: " + nonObj);
        }
    }

    private static void verifyOutcomeHttpMapping() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID waitId = UUID.randomUUID();
        UUID activationId = UUID.randomUUID();
        UUID successorId = UUID.randomUUID();
        UUID inboxId = UUID.randomUUID();

        ResponseEntity<String> completed = WorkflowRuntimeController.mapWaitSignalResponse(
                runId, "lims", "m-1", "qc.ok",
                new WorkflowRuntimeRepository.GraphWaitResult(
                        WaitOutcomes.COMPLETED, waitId, activationId, successorId, inboxId));
        require(completed.getStatusCode().value() == 200, "COMPLETED must map to 200");
        JsonNode cJson = JSON.readTree(completed.getBody());
        require(cJson.get("accepted").asBoolean()
                        && !cJson.get("replayed").asBoolean()
                        && !cJson.get("deferred").asBoolean()
                        && WaitOutcomes.COMPLETED.equals(cJson.get("outcome").asText())
                        && waitId.toString().equals(cJson.get("waitId").asText())
                        && successorId.toString().equals(cJson.get("successorTokenId").asText())
                        && inboxId.toString().equals(cJson.get("inboxId").asText()),
                "COMPLETED body fields mismatch: " + completed.getBody());

        ResponseEntity<String> replayed = WorkflowRuntimeController.mapWaitSignalResponse(
                runId, "lims", "m-1", "qc.ok",
                new WorkflowRuntimeRepository.GraphWaitResult(
                        WaitOutcomes.ALREADY_COMPLETED, null, null, null, inboxId));
        require(replayed.getStatusCode().value() == 200, "ALREADY_COMPLETED must map to 200");
        JsonNode rJson = JSON.readTree(replayed.getBody());
        require(rJson.get("accepted").asBoolean()
                        && rJson.get("replayed").asBoolean()
                        && !rJson.get("deferred").asBoolean()
                        && inboxId.toString().equals(rJson.get("inboxId").asText()),
                "ALREADY_COMPLETED body fields mismatch: " + replayed.getBody());

        ResponseEntity<String> deferred = WorkflowRuntimeController.mapWaitSignalResponse(
                runId, "lims", "m-2", "qc.ok",
                new WorkflowRuntimeRepository.GraphWaitResult(
                        WaitOutcomes.DEFERRED_RUN_NOT_OPEN, null, null, null, inboxId));
        require(deferred.getStatusCode().value() == 202, "DEFERRED_RUN_NOT_OPEN must map to 202");
        JsonNode dJson = JSON.readTree(deferred.getBody());
        require(dJson.get("accepted").asBoolean()
                        && !dJson.get("replayed").asBoolean()
                        && dJson.get("deferred").asBoolean(),
                "DEFERRED_RUN_NOT_OPEN body fields mismatch: " + deferred.getBody());

        ResponseEntity<String> notFound = WorkflowRuntimeController.mapWaitSignalResponse(
                runId, "lims", "m-3", "qc.ok",
                new WorkflowRuntimeRepository.GraphWaitResult(WaitOutcomes.NOT_FOUND, null, null, null, null));
        require(notFound.getStatusCode().value() == 404
                        && !JSON.readTree(notFound.getBody()).get("accepted").asBoolean(),
                "NOT_FOUND must map to 404 with accepted=false");

        for (String conflictOutcome : List.of(
                WaitOutcomes.PROTOCOL_VIOLATION, WaitOutcomes.REJECTED, WaitOutcomes.STALE_VERSION)) {
            ResponseEntity<String> conflict = WorkflowRuntimeController.mapWaitSignalResponse(
                    runId, "lims", "m-4", "qc.ok",
                    new WorkflowRuntimeRepository.GraphWaitResult(conflictOutcome, null, null, null, inboxId));
            JsonNode cfJson = JSON.readTree(conflict.getBody());
            require(conflict.getStatusCode().value() == 409
                            && !cfJson.get("accepted").asBoolean()
                            && conflictOutcome.equals(cfJson.get("outcome").asText())
                            && cfJson.hasNonNull("error"),
                    conflictOutcome + " must map to 409 with error message: " + conflict.getBody());
        }
    }

    private static int guard(String path, String method, String sessionRole,
                             String authHeader, HandlerMethod handler) throws Exception {
        ApiAuthInterceptor interceptor = new ApiAuthInterceptor(fakeAuth(sessionRole));
        Map<String, Object> attrs = new HashMap<>();
        int[] status = {200};
        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(
                WorkflowRuntimeSignalApiSelfTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getRequestURI" -> path;
                    case "getMethod" -> method;
                    case "getHeader" -> "Authorization".equalsIgnoreCase((String) a[0]) ? authHeader : null;
                    case "getRemoteAddr" -> "127.0.0.1";
                    case "setAttribute" -> {
                        attrs.put((String) a[0], a[1]);
                        yield null;
                    }
                    case "getAttribute" -> attrs.get((String) a[0]);
                    default -> defaultZero(m.getReturnType());
                });
        HttpServletResponse resp = (HttpServletResponse) Proxy.newProxyInstance(
                WorkflowRuntimeSignalApiSelfTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (p, m, a) -> switch (m.getName()) {
                    case "setStatus" -> {
                        status[0] = (Integer) a[0];
                        yield null;
                    }
                    case "getStatus" -> status[0];
                    case "getWriter" -> new PrintWriter(
                            new OutputStreamWriter(new ByteArrayOutputStream(), StandardCharsets.UTF_8), true);
                    default -> defaultZero(m.getReturnType());
                });
        boolean allowed = interceptor.preHandle(req, resp, handler);
        if (allowed && sessionRole != null) {
            Object attr = attrs.get(ApiAuthInterceptor.SESSION_ATTR);
            require(attr instanceof AuthService.Session s && sessionRole.equals(s.role()),
                    "Allowed request must populate SESSION_ATTR for role " + sessionRole);
        }
        return allowed ? 200 : status[0];
    }

    private static Object defaultZero(Class<?> rt) {
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AuthService fakeAuth(String role) throws Exception {
        AuthService auth = new AuthService(null, 1L);
        Field f = AuthService.class.getDeclaredField("sessions");
        f.setAccessible(true);
        Map<String, AuthService.Session> sessions = (Map<String, AuthService.Session>) f.get(auth);
        if (role != null) {
            sessions.put("tok", new AuthService.Session("tok", 1L, role + "-user", role, role,
                    Instant.now().plusSeconds(3600)));
        }
        return auth;
    }

    private static void require(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
        PASSED.incrementAndGet();
    }

    private WorkflowRuntimeSignalApiSelfTest() {}
}
