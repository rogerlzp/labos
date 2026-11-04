package com.labos.kernel.api;

import com.labos.kernel.device.DeviceModels;
import com.labos.kernel.engine.EngineDemo;
import com.labos.kernel.engine.NodeProgress;
import com.labos.kernel.engine.RecoverableNode;
import com.labos.kernel.engine.RunProgress;
import com.labos.kernel.engine.RunStateMachine;
import com.labos.kernel.engine.SimulatedBus;
import com.labos.kernel.engine.Sleeper;
import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.JdbcErrorOccurrenceStore;
import com.labos.kernel.persistence.JdbcEventStore;
import com.labos.kernel.persistence.JdbcRecoveryPolicyRepository;
import com.labos.kernel.persistence.Jsons;
import com.labos.kernel.persistence.Occurrence;
import com.labos.kernel.recovery.RecoveryDecider;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;
import com.labos.kernel.recovery.RecoveryStrategy;
import com.labos.kernel.workflow.PreflightChecker;
import com.labos.kernel.workflow.StartupRecovery;
import com.labos.kernel.workflow.RecipeCompiler;
import com.labos.kernel.driver.DriverRegistry;
import com.labos.kernel.driver.PositionLock;
import com.labos.kernel.driver.ShadowProbe;
import com.labos.kernel.driver.ShadowRunner;
import com.labos.kernel.workflow.BatchOrchestrator;
import com.labos.kernel.workflow.ExperimentExecutor;
import com.labos.kernel.workflow.ExperimentScheduler;
import com.labos.kernel.workflow.RunControl;
import com.labos.kernel.workflow.RuntimeHold;
import com.labos.kernel.workflow.SchedulerEta;
import com.labos.kernel.workflow.StatusVocabulary;
import com.labos.kernel.workflow.WorkflowBuilder;
import com.labos.kernel.workflow.WorkflowService;
import com.labos.kernel.workflow.RealWorkflowRunner.WorkflowDef;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.labos.kernel.api.ApiServer.*;


/**
 * MetaApi：从 {@link ApiServer#route} 拆出的 Meta 资源域路由（7 个路由块）。
 *
 * <p>路径：{@code /api/capabilities}、{@code /api/meta/device-models}、
 * {@code /api/meta/status-vocabulary}
 * <p>正则路由：{@code CAP_METHODS}
 *
 * <p><b>拆分约定</b>：路由顺序与原 ApiServer.route() 中完全一致；命中即 return true。
 * 共享 helper 仍由 ApiServer 提供（static import + {@code s.xxx}），不重复实现。
 */
final class MetaApi {

    /** 宿主：提供库连接/服务与共享 helper（与原 ApiServer 内部调用等价）。 */
    private final ApiServer s;

    MetaApi(ApiServer s) {
        this.s = s;
    }

    /**
     * 处理本资源域请求。
     *
     * @return true = 已处理（调用方直接 return）；false = 不归本域，交给下一个 controller
     */
    boolean handle(HttpExchange ex, String path, String method) throws Exception {
        // 状态词表（ROADMAP P2 状态映射收口）：前端 8 个页面的 状态→文案/颜色
        // 映射唯一权威。纯数据 + 纯函数，不读库；放在读侧元数据接口一组里。
        if ("/api/meta/status-vocabulary".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, StatusVocabulary.toJson());
            return true;
        }
        // M10 先行：型号库适配状态。现场/评审据此回答"哪些型号能对接真机、
        // 哪些只有占位、产线上有几台在跑却在等手册"；部署台数一次性 GROUP BY 查库，
        // 不在循环里逐型号查（避免 N+1）。
        if ("/api/meta/device-models".equals(path)) {
            s.requireGet(method, ex);
            Map<String, Integer> deployedByModel = new LinkedHashMap<>();
            try (Connection c = s.db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT model, count(*) AS n FROM device"
                                 + " WHERE tenant_id = ? AND deleted_at IS NULL"
                                 + " GROUP BY model")) {
                ps.setLong(1, TENANT);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        deployedByModel.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
            s.send(ex, 200, DeviceModels.libraryJson(deployedByModel));
            return true;
        }
        if ("/api/capabilities".equals(path)) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.capabilitiesJson());
            return true;
        }
        Matcher capMethods = CAP_METHODS.matcher(path);
        if (capMethods.matches()) {
            s.requireGet(method, ex);
            s.send(ex, 200, s.query.methodsJson(capMethods.group(1)));
            return true;
        }
        return false;
    }
    // ------------------------------------------------------------ 路由正则

    private static final Pattern CAP_METHODS = Pattern.compile("^/api/capabilities/([A-Z_]+)/methods$");
}
