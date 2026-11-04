package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 */
public final class BindingBackfill {

    private static final long TENANT = 1;

    public static void main(String[] args) throws Exception {
        boolean checkOnly = args.length > 0 && "--check".equals(args[0]);
        DbConfig db = DbConfig.fromEnv();
        int workflows = 0;
        int changed = 0;
        int mismatch = 0;

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id, graph->>'xml' AS xml FROM workflow
                      WHERE tenant_id = ? AND deleted_at IS NULL
                      ORDER BY id
                     """)) {
            ps.setLong(1, TENANT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String xml = rs.getString("xml");
                    if (xml == null || xml.isBlank()) {
                        continue;
                    }
                    workflows++;
                    BpmnGraphParser.ParsedGraph g;
                    try {
                        g = BpmnGraphParser.parse(xml);
                    } catch (RuntimeException e) {
                        System.out.println("  [跳过] 流程 " + id + " XML 解析失败：" + e.getMessage());
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
                            if (!matches(c, id, n.key(), rid, wantSeq)) {
                                mismatch++;
                                if (mismatch <= 20) {
                                    String[] got = actual(c, id, n.key());
                                    System.out.printf("  ✗ 流程 %d 节点 %s：XML 期望 rid=%s seq=%d，表里 rid=%s seq=%s%n",
                                            id, n.key(), rid, wantSeq,
                                            got == null ? "缺行" : got[0], got == null ? "缺行" : got[1]);
                                }
                            }
                        } else if (apply(c, id, n.key(), rid, wantSeq)) {
                            changed++;
                        }
                    }
                }
            }
        }

        if (checkOnly) {
            System.out.printf("%n比对 %d 条流程，不一致 %d 处%n", workflows, mismatch);
            System.exit(mismatch == 0 ? 0 : 1);
        }
        System.out.printf("%n回填 %d 条流程，更新 %d 行%n", workflows, changed);
    }

    private static String[] actual(Connection c, long wfId, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT resource_id, seq FROM workflow_node
                 WHERE tenant_id = ? AND workflow_id = ? AND node_key = ?
                """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, wfId);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Object r = rs.getObject("resource_id");
                Object q = rs.getObject("seq");
                return new String[]{String.valueOf(r), String.valueOf(q)};
            }
        }
    }

    private static boolean matches(Connection c, long wfId, String key, Long rid, int seq)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT resource_id, seq FROM workflow_node
                 WHERE tenant_id = ? AND workflow_id = ? AND node_key = ?
                """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, wfId);
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                long gotRid = rs.getLong("resource_id");
                boolean ridNull = rs.wasNull();
                int gotSeq = rs.getInt("seq");
                boolean seqNull = rs.wasNull();
                boolean ridOk = (rid == null && ridNull) || (rid != null && !ridNull && rid == gotRid);
                return ridOk && !seqNull && gotSeq == seq;
            }
        }
    }

    private static boolean apply(Connection c, long wfId, String key, Long rid, int seq)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE workflow_node SET resource_id = ?, seq = ?
                 WHERE tenant_id = ? AND workflow_id = ? AND node_key = ?
                   AND (resource_id IS DISTINCT FROM ? OR seq IS DISTINCT FROM ?)
                """)) {
            ps.setObject(1, rid);
            ps.setInt(2, seq);
            ps.setLong(3, TENANT);
            ps.setLong(4, wfId);
            ps.setString(5, key);
            ps.setObject(6, rid);
            ps.setInt(7, seq);
            return ps.executeUpdate() > 0;
        }
    }

    private BindingBackfill() {
    }
}
