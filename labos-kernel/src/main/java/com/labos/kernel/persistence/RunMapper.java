package com.labos.kernel.persistence;

/**
 * C 批：run 表的状态收尾 SQL，语句见 {@code mapper/RunMapper.xml}。
 *
 * <p>这几条是执行链上最敏感的写入（决定一次运行是 completed / failed /
 * paused），放进 XML 后能直接看清「改了哪些列、带了什么 WHERE 条件」，
 * 不用在 Java 里拼三段动态 SQL 字符串。
 */
public interface RunMapper {

    /** 执行期异常收尾：只改 status='running' 的行，不覆盖 paused。 */
    int markFailed(long tenantId, long runId, String errorJson);

    /** 正常/异常终止：completed 或 failed。 */
    int settleTerminal(long tenantId, long runId, String status);

    /** 转人工：paused + waiting_human（不写 actual_end）。 */
    int settleManual(long tenantId, long runId);

    /** P-09：执行体收尾，不再派发新命令。 */
    int markSettledPhase(long tenantId, long runId);

    /** 读当前状态（暂停边界判定等）。 */
    String selectStatus(long tenantId, long runId);
}
