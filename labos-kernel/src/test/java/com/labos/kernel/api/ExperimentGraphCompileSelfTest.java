package com.labos.kernel.api;

import java.util.List;

/**
 * 实验画布编译器自测。
 *
 * <p>锁住 S1 的核心前提：保存画布时，必须能稳定编译成流程顺序；
 * 一旦图形不满足当前支持的线性主链语义，要明确拒绝，而不是静默落错库。
 */
public final class ExperimentGraphCompileSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        section("1. 正常主链");
        ok("线性主链：开始 -> 两个流程 -> 结束",
                graph("""
                        {"id":"start-1","data":{"kind":"start","label":"开始"}},
                        {"id":"wf-1","data":{"kind":"workflow","workflowId":101,"label":"A"}},
                        {"id":"wf-2","data":{"kind":"workflow","workflowId":102,"label":"B"}},
                        {"id":"end-1","data":{"kind":"end","label":"结束"}}
                        """,
                        """
                        {"source":"start-1","target":"wf-1"},
                        {"source":"wf-1","target":"wf-2"},
                        {"source":"wf-2","target":"end-1"}
                        """),
                List.of(101L, 102L));

        ok("允许空实验：开始 -> 结束",
                graph("""
                        {"id":"start-1","data":{"kind":"start","label":"开始"}},
                        {"id":"end-1","data":{"kind":"end","label":"结束"}}
                        """,
                        """
                        {"source":"start-1","target":"end-1"}
                        """),
                List.of());

        section("2. 必须拒绝的图");
        throwsOn("流程节点未绑定 workflowId",
                graph("""
                        {"id":"start-1","data":{"kind":"start","label":"开始"}},
                        {"id":"wf-1","data":{"kind":"workflow","label":"A"}},
                        {"id":"end-1","data":{"kind":"end","label":"结束"}}
                        """,
                        """
                        {"source":"start-1","target":"wf-1"},
                        {"source":"wf-1","target":"end-1"}
                        """),
                "还没选具体流程");

        throwsOn("不支持 variable 节点混进主链",
                graph("""
                        {"id":"start-1","data":{"kind":"start","label":"开始"}},
                        {"id":"var-1","data":{"kind":"variable","label":"温度","value":"37"}},
                        {"id":"end-1","data":{"kind":"end","label":"结束"}}
                        """,
                        """
                        {"source":"start-1","target":"var-1"},
                        {"source":"var-1","target":"end-1"}
                        """),
                "只支持 开始/结束/流程");

        throwsOn("不支持分叉：开始节点不能分出 2 条线",
                graph("""
                        {"id":"start-1","data":{"kind":"start","label":"开始"}},
                        {"id":"wf-1","data":{"kind":"workflow","workflowId":101,"label":"A"}},
                        {"id":"wf-2","data":{"kind":"workflow","workflowId":102,"label":"B"}},
                        {"id":"end-1","data":{"kind":"end","label":"结束"}}
                        """,
                        """
                        {"source":"start-1","target":"wf-1"},
                        {"source":"start-1","target":"wf-2"},
                        {"source":"wf-1","target":"end-1"},
                        {"source":"wf-2","target":"end-1"}
                        """),
                "开始节点必须恰好连出 1 条线");

        throwsOn("存在孤立节点时拒绝保存",
                graph("""
                        {"id":"start-1","data":{"kind":"start","label":"开始"}},
                        {"id":"wf-1","data":{"kind":"workflow","workflowId":101,"label":"A"}},
                        {"id":"wf-2","data":{"kind":"workflow","workflowId":102,"label":"B"}},
                        {"id":"end-1","data":{"kind":"end","label":"结束"}}
                        """,
                        """
                        {"source":"start-1","target":"wf-1"},
                        {"source":"wf-1","target":"end-1"}
                        """),
                "流程节点必须各有 1 条入线和 1 条出线");

        throwsOn("存在回边时拒绝保存",
                graph("""
                        {"id":"start-1","data":{"kind":"start","label":"开始"}},
                        {"id":"wf-1","data":{"kind":"workflow","workflowId":101,"label":"A"}},
                        {"id":"wf-2","data":{"kind":"workflow","workflowId":102,"label":"B"}},
                        {"id":"end-1","data":{"kind":"end","label":"结束"}}
                        """,
                        """
                        {"source":"start-1","target":"wf-1"},
                        {"source":"wf-1","target":"wf-2"},
                        {"source":"wf-2","target":"wf-1"},
                        {"source":"wf-2","target":"end-1"}
                        """),
                "流程节点必须各有 1 条入线和 1 条出线");

        throwsOn("引用不存在节点的连线必须拒绝",
                graph("""
                        {"id":"start-1","data":{"kind":"start","label":"开始"}},
                        {"id":"end-1","data":{"kind":"end","label":"结束"}}
                        """,
                        """
                        {"source":"start-1","target":"wf-missing"}
                        """),
                "不存在的节点");

        section("3. 编译产物带偏序边（S2-A 迁移边界）");
        compilesEdges("线性主链：流程节点间 1 条偏序边",
                graph("""
                        {"id":"start-1","data":{"kind":"start","label":"开始"}},
                        {"id":"wf-1","data":{"kind":"workflow","workflowId":101,"label":"A"}},
                        {"id":"wf-2","data":{"kind":"workflow","workflowId":102,"label":"B"}},
                        {"id":"end-1","data":{"kind":"end","label":"结束"}}
                        """,
                        """
                        {"source":"start-1","target":"wf-1"},
                        {"source":"wf-1","target":"wf-2"},
                        {"source":"wf-2","target":"end-1"}
                        """),
                "wf-1>wf-2",
                "1:101,2:102");

        compilesEdges("空实验：无流程无边无步骤",
                graph("""
                        {"id":"start-1","data":{"kind":"start","label":"开始"}},
                        {"id":"end-1","data":{"kind":"end","label":"结束"}}
                        """,
                        """
                        {"source":"start-1","target":"end-1"}
                        """),
                "",
                "");

        System.out.printf("%n通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void ok(String name, String json, List<Long> expect) {
        try {
            List<Long> got = QueryService.compileExperimentGraphWorkflowIds(json);
            if (expect.equals(got)) {
                passed++;
                System.out.printf("  ✓ %-28s %s%n", name, got);
            } else {
                failed++;
                System.out.printf("  ✗ %-28s 期望 %s，实际 %s%n", name, expect, got);
            }
        } catch (RuntimeException e) {
            failed++;
            System.out.printf("  ✗ %-28s 不应抛异常：%s%n", name, firstLine(e.getMessage()));
        }
    }

    private static void throwsOn(String name, String json, String expectedPart) {
        try {
            List<Long> got = QueryService.compileExperimentGraphWorkflowIds(json);
            failed++;
            System.out.printf("  ✗ %-28s 应拒绝，却返回 %s%n", name, got);
        } catch (IllegalArgumentException e) {
            String msg = firstLine(e.getMessage());
            if (msg.contains(expectedPart)) {
                passed++;
                System.out.printf("  ✓ %-28s 拒绝：%s%n", name, msg);
            } else {
                failed++;
                System.out.printf("  ✗ %-28s 拒绝原因不符：%s%n", name, msg);
            }
        }
    }

    /** 校验编译出的偏序边与步骤（用于 S2-A 迁移边界）。 */
    private static void compilesEdges(String name, String json,
                                      String expectEdges, String expectSteps) {
        try {
            QueryService.CompiledGraph g = QueryService.compileExperimentGraph(json);
            String edges = g.edges().stream()
                    .map(e -> e.fromNodeId() + ">" + e.toNodeId())
                    .reduce((a, b) -> a + "," + b).orElse("");
            String steps = g.steps().stream()
                    .map(s -> s.seq() + ":" + s.workflowId())
                    .reduce((a, b) -> a + "," + b).orElse("");
            if (expectEdges.equals(edges) && expectSteps.equals(steps)) {
                passed++;
                System.out.printf("  ✓ %-28s edges=[%s] steps=[%s]%n", name, edges, steps);
            } else {
                failed++;
                System.out.printf("  ✗ %-28s 期望 edges=[%s] steps=[%s]，实际 edges=[%s] steps=[%s]%n",
                        name, expectEdges, expectSteps, edges, steps);
            }
        } catch (RuntimeException e) {
            failed++;
            System.out.printf("  ✗ %-28s 不应抛异常：%s%n", name, firstLine(e.getMessage()));
        }
    }

    private static String graph(String nodes, String edges) {
        return """
                {
                  "nodes": [%s],
                  "edges": [%s]
                }
                """.formatted(nodes, edges);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i >= 0 ? s.substring(0, i) : s;
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private ExperimentGraphCompileSelfTest() {
    }
}
