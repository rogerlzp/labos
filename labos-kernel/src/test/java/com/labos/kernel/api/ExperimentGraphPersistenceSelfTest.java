package com.labos.kernel.api;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * S1 连库自测：画布保存要和 process/version 同事务更新。
 *
 * <p>锁住两条不能退的语义：
 * <ol>
 *   <li>保存成功：graph / process / version 一起更新</li>
 *   <li>保存失败：graph / process / version 都保持原样（证明事务回滚）</li>
 * </ol>
 */
public final class ExperimentGraphPersistenceSelfTest {

    private static final long TENANT = 1L;

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static QueryService query;
    private static long experimentId;
    private static String experimentNo;
    private static List<Long> workflowIds;

    public static void main(String[] args) throws Exception {
        String host = System.getenv("LABOS_DB_HOST");
        if (host == null || host.isBlank()) {
            System.out.println("  ○ 未设置 LABOS_DB_HOST，跳过（连库验证必须显式指定目标库）");
            System.out.println("通过 0, 失败 0");
            return;
        }
        db = DbConfig.fromEnv();
        query = new QueryService(db, TENANT);

        workflowIds = loadWorkflowIds();
        if (workflowIds.size() < 2) {
            System.out.println("通过 0, 失败 0");
            return;
        }

        createExperimentFixture();
        try {
            testSaveGraphCompilesProcessAndBumpsVersion();
            testFailedSaveRollsBackGraphProcessAndVersion();
            testResaveShrinksEdgesAndDropsOrphans();
        } finally {
            cleanup();
        }

        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void testSaveGraphCompilesProcessAndBumpsVersion() throws Exception {
        int beforeVersion = experimentVersion();
        String json = linearGraph(workflowIds.get(0), workflowIds.get(1));
        List<Long> expected = List.of(workflowIds.get(0), workflowIds.get(1));

        int compiled = query.saveExperimentGraph(experimentId, json);

        check("保存成功返回 2 条流程", compiled == 2, compiled);
        check("graph 已落库且语义未变", storedGraphCompilesTo(expected), graphJson());
        check("process 顺序已编译", processIds().equals(expected),
                processIds());
        check("偏序边已落库：2 个流程 = 1 条链式边",
                edgePairs().equals(List.of(workflowIds.get(0) + ">" + workflowIds.get(1))),
                edgePairs());
        check("version + 1", experimentVersion() == beforeVersion + 1,
                beforeVersion + " -> " + experimentVersion());
    }

    private static void testFailedSaveRollsBackGraphProcessAndVersion() throws Exception {
        String graphBefore = graphJson();
        List<Long> processBefore = processIds();
        List<String> edgesBefore = edgePairs();
        int versionBefore = experimentVersion();

        boolean rejected = false;
        try {
            query.saveExperimentGraph(experimentId, linearGraph(workflowIds.get(0), 999999999L));
        } catch (IllegalArgumentException e) {
            rejected = e.getMessage() != null && e.getMessage().contains("流程不存在或已删除");
        }

        check("非法 workflowId 被拒绝", rejected, "未抛出预期错误");
        check("失败后 graph 未变化", compact(graphJson()).equals(compact(graphBefore)), graphJson());
        check("失败后 process 未变化", processIds().equals(processBefore), processIds());
        check("失败后偏序边未变化", edgePairs().equals(edgesBefore), edgePairs());
        check("失败后 version 未变化", experimentVersion() == versionBefore,
                versionBefore + " -> " + experimentVersion());
    }

    /** 重存更短的编排：先前边必须随 process 一起消失，不能留孤儿。 */
    private static void testResaveShrinksEdgesAndDropsOrphans() throws Exception {
        int compiled = query.saveExperimentGraph(experimentId, oneWorkflowGraph(workflowIds.get(0)));
        check("重存为单流程：编译 1 条流程", compiled == 1, compiled);
        check("重存后边降为 0（随 process 级联清理）", edgePairs().isEmpty(), edgePairs());
        check("重存后 process 只剩 1 条",
                processIds().equals(List.of(workflowIds.get(0))), processIds());
    }

    private static List<Long> loadWorkflowIds() throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id
                     FROM workflow
                     WHERE tenant_id = ?
                     ORDER BY id
                     LIMIT 2
                     """)) {
            ps.setLong(1, TENANT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private static void createExperimentFixture() throws Exception {
        experimentNo = "EXP-S1-SELFTEST-" + System.currentTimeMillis();
        query.createExperiment("S1 SelfTest", experimentNo, null, 5, List.of(workflowIds.get(0)));
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id
                     FROM experiment
                     WHERE tenant_id = ? AND experiment_no = ?
                     """)) {
            ps.setLong(1, TENANT);
            ps.setString(2, experimentNo);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("测试实验创建失败");
                }
                experimentId = rs.getLong(1);
            }
        }
    }

    private static int experimentVersion() throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT version
                     FROM experiment
                     WHERE tenant_id = ? AND id = ?
                     """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static List<Long> processIds() throws Exception {
        List<Long> ids = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT workflow_id
                     FROM experiment_process
                     WHERE tenant_id = ? AND experiment_id = ?
                     ORDER BY seq
                     """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /** 偏序边（翻译回 workflow_id 便于阅读）：from>to，按前驱 seq 排序。 */
    private static List<String> edgePairs() throws Exception {
        List<String> pairs = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT pf.workflow_id AS from_wf, pt.workflow_id AS to_wf
                     FROM experiment_process_edge e
                     JOIN experiment_process pf ON pf.id = e.from_id
                     JOIN experiment_process pt ON pt.id = e.to_id
                     WHERE e.tenant_id = ? AND e.experiment_id = ?
                     ORDER BY pf.seq
                     """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pairs.add(rs.getLong(1) + ">" + rs.getLong(2));
                }
            }
        }
        return pairs;
    }

    private static String graphJson() throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT graph::text
                     FROM experiment_graph
                     WHERE tenant_id = ? AND experiment_id = ?
                     """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String linearGraph(long wf1, long wf2) {
        return """
                {
                  "nodes": [
                    {"id":"start-1","data":{"kind":"start","label":"开始"}},
                    {"id":"wf-1","data":{"kind":"workflow","workflowId":%d,"label":"A"}},
                    {"id":"wf-2","data":{"kind":"workflow","workflowId":%d,"label":"B"}},
                    {"id":"end-1","data":{"kind":"end","label":"结束"}}
                  ],
                  "edges": [
                    {"source":"start-1","target":"wf-1"},
                    {"source":"wf-1","target":"wf-2"},
                    {"source":"wf-2","target":"end-1"}
                  ]
                }
                """.formatted(wf1, wf2).replace("\n", "").replace("  ", "");
    }

    /** 只有 1 个流程的线性图（重存缩边用）。 */
    private static String oneWorkflowGraph(long wf) {
        return """
                {
                  "nodes": [
                    {"id":"start-1","data":{"kind":"start","label":"开始"}},
                    {"id":"wf-1","data":{"kind":"workflow","workflowId":%d,"label":"A"}},
                    {"id":"end-1","data":{"kind":"end","label":"结束"}}
                  ],
                  "edges": [
                    {"source":"start-1","target":"wf-1"},
                    {"source":"wf-1","target":"end-1"}
                  ]
                }
                """.formatted(wf).replace("\n", "").replace("  ", "");
    }

    private static boolean storedGraphCompilesTo(List<Long> expected) {
        try {
            String stored = graphJson();
            return stored != null && QueryService.compileExperimentGraphWorkflowIds(stored).equals(expected);
        } catch (Exception e) {
            return false;
        }
    }

    private static String compact(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static void cleanup() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     DELETE FROM experiment
                     WHERE tenant_id = ? AND id = ?
                     """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, experimentId);
            ps.executeUpdate();
        } catch (Exception e) {
            // 清理失败不影响结论；ON DELETE CASCADE 会一并带走 graph/process。
        }
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name + "（实际：" + actual + "）");
        }
    }

    private ExperimentGraphPersistenceSelfTest() {
    }
}
