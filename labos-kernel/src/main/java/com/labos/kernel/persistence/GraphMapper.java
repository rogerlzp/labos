package com.labos.kernel.persistence;

/**
 * 流程图相关 SQL，语句见 {@code mapper/GraphMapper.xml}。
 *
 * <p>这里收的是「保存流程图」这条写路径上最容易出半成品的几条：
 * 备份上一版、清旧边、读当前 XML。以前它们散在 {@code WorkflowService}
 * 的多个 {@code try (Connection c = db.open())} 里，备份甚至发生在正式
 * 保存的事务之外 —— 备份成功但保存失败，历史里就多一条"上一版"，
 * 实际图并没换。现在统一由 {@code @Transactional} 管，SQL 也集中到 XML。
 */
public interface GraphMapper {

    /** 把当前图存一份到历史表（存在才写，无图时 0 行）。 */
    int backupGraph(long tenantId, long workflowId);

    /** 清掉上一版边，随后按新图重建。 */
    int deleteEdges(long workflowId);

    /** 读当前图 XML。 */
    String selectGraphXml(long tenantId, long workflowId);
}
