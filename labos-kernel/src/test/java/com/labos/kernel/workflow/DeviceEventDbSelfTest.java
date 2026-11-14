package com.labos.kernel.workflow;

import com.labos.kernel.engine.NodeStatus;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.SimulatedBus;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.event.MapperDeviceEventSource;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.DeviceEventMapper;
import com.labos.kernel.persistence.Mappers;
import com.labos.kernel.persistence.RunMapper;
import com.labos.kernel.recovery.Backoff;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import com.labos.kernel.workflow.WorkflowGraphParser.Graph;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 设备事件 + 外部写入的**连库**判别性自测（2026-11-15）。
 *
 * <p><b>为什么纯逻辑自测不够</b>：纯逻辑自测里，变量刷新源和事件源都是内存实现，
 * 证明的是「引擎的语义对」。但真正要验的是**整条链路**：
 * <pre>
 *   外部写入 → run.input.vars → 刷新源 → __wait 通过
 *   设备上报 → device_event 表 → 事件源 → __awaitEvent 通过（带载荷）
 * </pre>
 * 中间隔着 <b>SQL</b>（{@code run.input->'vars'} 的 jsonb 读写、{@code device_event} 的增删查）
 * —— 这些只有连库才验得到。
 *
 * <p>钉住的三件事：
 * <ol>
 *   <li>{@code RunMapper.setRunVar} 写的值能被 {@code selectRunVars} 读回来
 *       （jsonb 单键合并，不是整表覆盖）；</li>
 *   <li>外部写入能<b>唤醒</b>一个正在等的 {@code __wait}（不是只能等超时）；</li>
 *   <li>{@code __awaitEvent} 能从**真表**里捞到事件，载荷与设备码交给下游。</li>
 * </ol>
 *
 * <p>连库跑法：{@code LABOS_DB_HOST} 等环境变量齐全时才有意义（见 {@code scripts/test-all.sh}）。
 */
public final class DeviceEventDbSelfTest {

    private static final long TENANT = 1L;
    private static final long RUN = -996001L;
    private static final String DEV_CODE = "ID-VERIFY-EXT1";

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        System.out.println("════ 设备事件 / 外部写入 连库自测 ════");
        cleanup();
        try {
            seed();

            section("1. setRunVar 写进去的单键能被 selectRunVars 读回来");
            RunMapper rm = Mappers.get(RunMapper.class);
            int n = rm.setRunVar(TENANT, RUN, "gate", "1");
            Map<String, String> vars = new java.util.LinkedHashMap<>();
            for (Map<String, Object> row : rm.selectRunVars(TENANT, RUN)) {
                Object k = row.get("name");
                Object v = row.get("value");
                if (k != null) {
                    vars.put(String.valueOf(k), v == null ? "" : String.valueOf(v));
                }
            }
            if (n > 0 && "1".equals(vars.get("gate"))) {
                ok("变量写入 → 读回（gate=1）", vars.toString());
            } else {
                bad("变量写入/读回不符", "n=" + n + " vars=" + vars);
            }

            section("2. 外部写入能唤醒正在等的 __wait（不是只能等超时）");
            // 刷新源直接读真库 ⇒ 这条验的是「SQL 那一段」而不是内存实现
            AtomicGate gate = new AtomicGate(rm);
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                 id="D1" targetNamespace="http://bpmn.io/schema/bpmn">
                      <process id="P1" isExecutable="false">
                        <startEvent id="S"/>
                        <task id="W" name="等就绪" protocolName="__wait"/>
                        <endEvent id="E"/>
                        <sequenceFlow id="F1" sourceRef="S" targetRef="W"/>
                        <sequenceFlow id="F2" sourceRef="W" targetRef="E"/>
                      </process>
                    </definitions>
                    """;
            Graph g = WorkflowGraphParser.parse(xml);
            RunStateMachine sm = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            SimulatedBus bus = new SimulatedBus();
            GraphRunner runner = new GraphRunner(sm,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSleeper(Sleeper.recording(new ArrayList<>()))
                    .withVariableRefresher(gate::read)
                    .withBuiltinParams(Map.of("W", Map.of(
                            "var", "gate", "value", "1",
                            "timeout", "10", "interval", "1")));
            RunProgress p = runner.run(g, bus);
            if (p.count(NodeStatus.MANUAL) == 0 && p.count(NodeStatus.COMPLETED) == 1) {
                ok("__wait 通过（读到真库里的 gate=1）", "polls=" + gate.polls);
            } else {
                bad("__wait 没通过（外部写入没唤醒）",
                        "manual=" + p.count(NodeStatus.MANUAL)
                                + " completed=" + p.count(NodeStatus.COMPLETED));
            }

            section("3. __awaitEvent 从真表里捞到事件，载荷与设备码交给下游");
            DeviceEventMapper dem = Mappers.get(DeviceEventMapper.class);
            dem.insertEvent(TENANT, null, DEV_CODE, "waiting_interaction",
                    "{\"station\":\"exchange\"}", null);
            MapperDeviceEventSource src = new MapperDeviceEventSource(TENANT, dem);
            long base = src.currentMaxEventId();
            // 游标取 base ⇒ 只看 base 之后的新事件；先插一条"存量"再插一条"新的"
            dem.insertEvent(TENANT, null, DEV_CODE, "waiting_interaction", "{\"old\":1}", null);
            long afterOld = src.currentMaxEventId();
            dem.insertEvent(TENANT, null, DEV_CODE, "waiting_interaction",
                    "{\"station\":\"exchange\",\"seq\":2}", null);

            String xml2 = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                 id="D2" targetNamespace="http://bpmn.io/schema/bpmn">
                      <process id="P2" isExecutable="false">
                        <startEvent id="S"/>
                        <task id="W" name="等设备事件" protocolName="__awaitEvent"/>
                        <endEvent id="E"/>
                        <sequenceFlow id="F1" sourceRef="S" targetRef="W"/>
                        <sequenceFlow id="F2" sourceRef="W" targetRef="E"/>
                      </process>
                    </definitions>
                    """;
            Graph g2 = WorkflowGraphParser.parse(xml2);
            RunStateMachine sm2 = new RunStateMachine(decider(), e -> { },
                    Sleeper.recording(new ArrayList<>()), Clock.systemUTC());
            GraphRunner runner2 = new GraphRunner(sm2,
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSleeper(Sleeper.recording(new ArrayList<>()))
                    .withDeviceEventSource(src)
                    // 游标取 afterOld ⇒ 跳过"存量"那条，只吃之后新来的
                    .withEventBaseCursor(afterOld)
                    .withBuiltinParams(Map.of("W", Map.of(
                            "device", DEV_CODE, "eventType", "waiting_interaction",
                            "timeout", "10", "interval", "1",
                            "into", "payload", "deviceInto", "which")));
            RunProgress p2 = runner2.run(g2, new SimulatedBus());
            String payload = runner2.variableValue("payload");
            String which = runner2.variableValue("which");
            if (p2.count(NodeStatus.MANUAL) == 0 && p2.count(NodeStatus.COMPLETED) == 1
                    && payload != null && payload.contains("seq")
                    && !payload.contains("old")
                    && DEV_CODE.equals(which)) {
                ok("__awaitEvent 通过，拿到的是新事件而不是存量",
                        "payload=" + payload + " which=" + which);
            } else {
                bad("__awaitEvent 取错了事件或没通过",
                        "manual=" + p2.count(NodeStatus.MANUAL)
                                + " completed=" + p2.count(NodeStatus.COMPLETED)
                                + " payload=" + payload + " which=" + which);
            }

            section("4. 游标之前的历史存量不会被当成新事件（反例）");
            GraphRunner runner3 = new GraphRunner(new RunStateMachine(decider(), e -> { },
                            Sleeper.recording(new ArrayList<>()), Clock.systemUTC()),
                    Map.of("TRANSFER", List.of(101L)),
                    Map.of("Transfer", "TRANSFER"),
                    s -> { })
                    .withSleeper(Sleeper.recording(new ArrayList<>()))
                    .withDeviceEventSource(src)
                    // 游标取最新 ⇒ 表里所有存量都在游标之前 ⇒ 等不到
                    .withEventBaseCursor(src.currentMaxEventId())
                    .withBuiltinParams(Map.of("W", Map.of(
                            "device", DEV_CODE, "eventType", "waiting_interaction",
                            "timeout", "3", "interval", "1")));
            RunProgress p3 = runner3.run(g2, new SimulatedBus());
            if (p3.count(NodeStatus.MANUAL) == 1) {
                ok("存量事件不会被当成新上报（转人工 = 没等到新的）", "ok");
            } else {
                bad("存量事件被当成了新上报",
                        "manual=" + p3.count(NodeStatus.MANUAL));
            }
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 刷新源：每轮从**真库**读一次运行期变量。 */
    private static final class AtomicGate {
        private final RunMapper rm;
        int polls;

        AtomicGate(RunMapper rm) {
            this.rm = rm;
        }

        Map<String, String> read() {
            polls++;
            Map<String, String> out = new java.util.LinkedHashMap<>();
            for (Map<String, Object> row : rm.selectRunVars(TENANT, RUN)) {
                Object k = row.get("name");
                Object v = row.get("value");
                if (k != null) {
                    out.put(String.valueOf(k), v == null ? "" : String.valueOf(v));
                }
            }
            return out;
        }
    }

    // ================================================================ 造数 / 清理

    private static void seed() throws SQLException {
        exec("INSERT INTO run (id, tenant_id, run_no, run_type, input) "
                + "VALUES (?, ?, 'VERIFY-DEVEVENT', 'workflow', '{\"vars\":{\"gate\":\"0\"}}'::jsonb)",
                RUN, TENANT);
    }

    private static void cleanup() {
        try {
            exec("DELETE FROM device_event WHERE device_code = ?", DEV_CODE);
            exec("DELETE FROM run WHERE id = ?", RUN);
        } catch (RuntimeException ignore) {
            // 清理失败不掩盖正文结果
        }
    }

    private static void exec(String sql, Object... args) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("SQL 失败: " + sql + " :: " + e.getMessage(), e);
        }
    }

    // ================================================================ 断言小工具

    private static void section(String s) {
        System.out.println();
        System.out.println("── " + s);
    }

    private static void ok(String what, String detail) {
        pass++;
        System.out.println("  ✔ " + what + (detail == null ? "" : "  [" + detail + "]"));
    }

    private static void bad(String what, String detail) {
        fail++;
        System.out.println("  ✘ " + what + "  [" + detail + "]");
    }

    private static RecoveryDecider decider() {
        return new RecoveryDecider(new RecoveryPolicyResolver(List.of(
                new RecoveryPolicy(null, RecoveryScope.NODE, RecoveryStrategy.REASSIGN,
                        2, Backoff.FIXED, Duration.ZERO, 20, true),
                new RecoveryPolicy(null, null, RecoveryStrategy.MANUAL,
                        0, Backoff.FIXED, Duration.ZERO, 99, true))));
    }
}
