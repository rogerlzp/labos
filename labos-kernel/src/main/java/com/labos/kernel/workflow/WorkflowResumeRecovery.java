package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * FIX-P0-04：启动时把 commit 后遗留的 queued workflow run 重新交给续跑器。
 */
public final class WorkflowResumeRecovery {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WorkflowResumeRecovery.class);

    @FunctionalInterface
    public interface ResumeStarter {
        void start(long runId) throws Exception;
    }

    public record Result(int scanned, int started, int failed) {
    }

    public static Result rehydrateQueued(DbConfig db, long tenantId, ResumeStarter starter)
            throws SQLException {
        List<Long> runIds = queuedWorkflowRunIds(db, tenantId);
        int started = 0;
        int failed = 0;
        for (long runId : runIds) {
            try {
                starter.start(runId);
                started++;
            } catch (Exception e) {
                failed++;
                log.error("[启动收尾] queued workflow rehydrate 失败：run#" + runId
                        + " -> " + e.getMessage());
            }
        }
        return new Result(runIds.size(), started, failed);
    }

    static List<Long> queuedWorkflowRunIds(DbConfig db, long tenantId) throws SQLException {
        return new ArrayList<>(com.labos.kernel.persistence.Mappers.get(
                com.labos.kernel.persistence.RunMapper.class)
                .selectQueuedWorkflowRunIds(tenantId));
    }

    private WorkflowResumeRecovery() {
    }
}
