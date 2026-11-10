package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.GraphMapper;
import com.labos.kernel.persistence.Mappers;

import java.util.Map;

/**
 * 把图 XML 里的**设备绑定**与**顺序**回填到 {@code workflow_node.resource_id / seq}。
 *
 * <p>为什么要有这个工具：这两样改动前只有解析 XML 才拿得到，
 * ETA、时间条、节点绑定面板各自写过一遍正则（迟早不一致）。
 * 落表之后读侧只查列；但存量流程的列是空的，需要跑一次回填。
 *
 * <p>用法：
 * <pre>
 *   java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.workflow.BindingBackfill          # 回填
 *   java -cp out:lib/postgresql-42.7.3.jar com.labos.kernel.workflow.BindingBackfill --check   # 只比对，不一致则退出码 1
 * </pre>
 *
 * <p>幂等：重复跑只会把列刷成 XML 的当前值；不一致的行会打印出来（最多 20 行）。
 *
 * <p>阶段3第20批：4 条裸 SQL 收编进 {@code GraphMapper}（见
 * {@code mapper/GraphMapper.xml} 的 BindingBackfill 段）。本工具是无容器入口，
 * {@link Mappers#get} 自动以 {@link DbConfig#standaloneDataSource()} 初始化，
 * 语句各自 autocommit —— 与原「单连接 + 逐条执行」语义一致。
 */
public final class BindingBackfill {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(BindingBackfill.class);
    /** 兼容原有 System.out.printf 的格式化输出。 */
    private static void printf(String format, Object... args) {
        log.info(String.format(format, args));
    }

    private static final long TENANT = 1;

    private static GraphMapper mapper() {
        return Mappers.get(GraphMapper.class);
    }

    public static void main(String[] args) throws Exception {
        boolean checkOnly = args.length > 0 && "--check".equals(args[0]);
        int workflows = 0;
        int changed = 0;
        int mismatch = 0;

        for (Map<String, Object> row : mapper().selectBackfillWorkflowXmls(TENANT)) {
            long id = ((Number) row.get("id")).longValue();
            String xml = (String) row.get("xml");
            if (xml == null || xml.isBlank()) {
                continue;
            }
            workflows++;
            BpmnGraphParser.ParsedGraph g;
            try {
                g = BpmnGraphParser.parse(xml);
            } catch (RuntimeException e) {
                log.info("  [跳过] 流程 " + id + " XML 解析失败：" + e.getMessage());
                continue;
            }
            // seq 必须按 **XML 文档顺序**：解析器内部的 Map 顺序在两次运行之间会漂移
            // （回填一遍、检查一遍就对不上，2026-11-03 踩过）。
            Map<String, Integer> order = BpmnGraphParser.documentOrder(xml,
                    g.nodes().stream().map(BpmnGraphParser.ParsedNode::key).toList());
            for (BpmnGraphParser.ParsedNode n : g.nodes()) {
                Long rid = n.resourceId();
                Integer wantSeq = order.get(n.key());
                if (wantSeq == null) {
                    continue;
                }
                if (checkOnly) {
                    if (!matches(id, n.key(), rid, wantSeq)) {
                        mismatch++;
                        if (mismatch <= 20) {
                            printf("  ✗ 流程 %d 节点 %s：XML 期望 rid=%s seq=%d，表里 %s%n",
                                    id, n.key(), rid, wantSeq, actualDesc(id, n.key()));
                        }
                    }
                } else if (mapper().updateNodeBinding(TENANT, id, n.key(), rid, wantSeq) > 0) {
                    changed++;
                }
            }
        }

        if (checkOnly) {
            printf("%n比对 %d 条流程，不一致 %d 处%n", workflows, mismatch);
            System.exit(mismatch == 0 ? 0 : 1);
        }
        printf("%n回填 %d 条流程，更新 %d 行%n", workflows, changed);
    }

    /** 比对：行不存在 / rid 不等（含双侧 NULL）/ seq 不等都算不一致（原 matches 语义）。 */
    private static boolean matches(long wfId, String key, Long rid, int seq) {
        Map<String, Object> row = mapper().selectNodeBinding(TENANT, wfId, key);
        if (row == null) {
            return false;
        }
        Object gotRid = row.get("resourceId");
        Object gotSeq = row.get("seq");
        boolean ridOk = (rid == null && gotRid == null)
                || (rid != null && gotRid != null
                        && ((Number) gotRid).longValue() == rid);
        return ridOk && gotSeq != null && ((Number) gotSeq).intValue() == seq;
    }

    /** 不一致打印用的现状描述（原 actual 的 String[]{rid, seq}，“缺行”原样保留）。 */
    private static String actualDesc(long wfId, String key) {
        Map<String, Object> row = mapper().selectNodeBinding(TENANT, wfId, key);
        if (row == null) {
            return "rid=缺行 seq=缺行";
        }
        return "rid=" + row.get("resourceId") + " seq=" + row.get("seq");
    }

    private BindingBackfill() {
    }
}
