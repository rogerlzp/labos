package com.labos.boot.web.service;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.workflow.ExperimentScheduler;
import com.labos.kernel.workflow.PauseCoordinator;
import com.labos.kernel.workflow.WorkflowService;
import org.springframework.stereotype.Component;

/**
 * {@link PauseCoordinator} 的唯一构造点。
 *
 * <p>为什么用工厂而不是把它做成单例 Bean：{@code PauseCoordinator} 是
 * **按租户**构造的（{@code tenantId} 是实例字段，内部几十处 SQL 都带着它），
 * 而暂停语义是这套系统里最敏感的一段（PauseCoordinatorDbSelfTest 72 条断言
 * 钉着）。把它改成无状态单例、把 tenantId 逐方法下传，等于重写整个类；
 * 用工厂换掉「谁都能 new 一个」的现状，收益/风险比更划算：
 * 构造参数（db / workflows / scheduler）只在这里绑定一次，
 * Controller 与服务层不再各自拼装，出问题只需要看这一个地方。
 */
@Component
public class PauseCoordinatorFactory {

    private final DbConfig db;
    private final WorkflowService workflows;
    private final ExperimentScheduler scheduler;

    public PauseCoordinatorFactory(DbConfig db, WorkflowService workflows,
                                   ExperimentScheduler scheduler) {
        this.db = db;
        this.workflows = workflows;
        this.scheduler = scheduler;
    }

    /** 指定租户的暂停协调器。 */
    public PauseCoordinator forTenant(long tenantId) {
        return new PauseCoordinator(db, tenantId, workflows, scheduler);
    }
}
