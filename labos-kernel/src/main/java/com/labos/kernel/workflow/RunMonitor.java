package com.labos.kernel.workflow;

import com.labos.kernel.engine.EventType;
import com.labos.kernel.engine.ExecutionEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行态监控：回答「这个 run 现在卡在哪」。
 *
 * <p>为什么需要它：`run_node` 只在执行结束时批量落库，
 * 所以执行期间查库看不到任何节点——Process Status View 就无从回答。
 *
 * <p>这里用内存维护当前进度（同一 JVM 内），执行结束即移除。
 * 内存丢失（重启）不影响业务，只是"为什么停"的诊断信息没了。
 */
public final class RunMonitor {

    public static final RunMonitor INSTANCE = new RunMonitor();

    /** 单个 run 的实时快照（可变，只在执行线程写）。 */
    public static final class Live {
        public final long runId;
        public final String runNo;
        public final String attendance;
        public final Instant startedAt = Instant.now();
        public volatile int total;
        public volatile int done;
        public volatile int manual;
        public volatile String nodeKey = "";
        public volatile String nodeName = "";
        public volatile String nodeStatus = "waiting";
        public volatile Long resourceId;
        public volatile String reason = "";
        public volatile String lastEvent = "";

        Live(long runId, String runNo, String attendance) {
            this.runId = runId;
            this.runNo = runNo;
            this.attendance = attendance;
        }
    }

    private static final java.util.regex.Pattern RES =
            java.util.regex.Pattern.compile("resource=(\\d+)");

    private final Map<Long, Live> live = new ConcurrentHashMap<>();

    public void start(long runId, String runNo, String attendance, int total) {
        Live l = new Live(runId, runNo, attendance);
        l.total = total;
        live.put(runId, l);
    }

    /** 接收状态机事件，更新"当前在哪个节点"。 */
    public void onEvent(long runId, ExecutionEvent e) {
        Live l = live.get(runId);
        if (l == null) {
            return;
        }
        l.lastEvent = e.type().name();
        switch (e.type()) {
            case NODE_STARTED -> {
                l.nodeKey = e.nodeKey();
                l.nodeName = e.nodeKey();
                l.nodeStatus = "running";
                // detail 形如 "node=27890 resource=200 capability=READ_PLATE"
                java.util.regex.Matcher m = RES.matcher(e.detail() == null ? "" : e.detail());
                if (m.find()) {
                    l.resourceId = Long.parseLong(m.group(1));
                }
            }
            case NODE_COMPLETED -> {
                l.done++;
                l.nodeStatus = "completed";
            }
            case STEP_FAILED -> {
                l.nodeStatus = "failed";
                l.reason = e.detail();
            }
            case RECOVERY_DECIDED -> {
                l.nodeStatus = "recovering";
                l.reason = e.detail();
            }
            case RESOURCE_SWITCHED -> l.reason = e.detail();
            case NODE_MANUAL -> {
                l.manual++;
                l.nodeStatus = "manual";
                l.reason = e.detail();
            }
            case NODE_SKIPPED -> l.nodeStatus = "skipped";
            case RUN_ABORTED -> l.nodeStatus = "aborted";
            default -> {
                // 其他事件不改变"当前节点"
            }
        }
    }

    /** 记录当前分配到的设备。 */
    public void resource(long runId, Long resourceId) {
        Live l = live.get(runId);
        if (l != null) {
            l.resourceId = resourceId;
        }
    }

    public void finish(long runId) {
        live.remove(runId);
    }

    /** 正在跑的 run（按开始时间倒序）。 */
    public List<Live> running() {
        List<Live> out = new ArrayList<>(live.values());
        out.sort(Comparator.comparing((Live x) -> x.startedAt).reversed());
        return out;
    }

    public Live get(long runId) {
        return live.get(runId);
    }

    private RunMonitor() {
    }
}
