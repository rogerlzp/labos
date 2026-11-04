package com.labos.kernel.api;

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
 * AuthApi：从 {@link ApiServer#route} 拆出的 Auth 资源域路由（3 个路由块）。
 *
 * <p>路径：{@code /api/auth/login}、{@code /api/auth/logout}、{@code /api/auth/me}
 *
 * <p><b>拆分约定</b>：路由顺序与原 ApiServer.route() 中完全一致；命中即 return true。
 * 共享 helper 仍由 ApiServer 提供（static import + {@code s.xxx}），不重复实现。
 */
final class AuthApi {

    /** 宿主：提供库连接/服务与共享 helper（与原 ApiServer 内部调用等价）。 */
    private final ApiServer s;

    AuthApi(ApiServer s) {
        this.s = s;
    }

    /**
     * 处理本资源域请求。
     *
     * @return true = 已处理（调用方直接 return）；false = 不归本域，交给下一个 controller
     */
    boolean handle(HttpExchange ex, String path, String method) throws Exception {
        if ("/api/auth/login".equals(path)) {
            if (!"POST".equals(method)) {
                throw new IllegalStateException("method not allowed: " + method);
            }
            String body = new String(s.readBody(ex), StandardCharsets.UTF_8);
            String u = jsonField(body, "username");
            String pw = jsonField(body, "password");
            AuthService.Session sess = s.auth.login(u, pw, remoteIp(ex));
            if (sess == null) {
                s.send(ex, 401, err("用户名或密码错误"));
                return true;
            }
            s.send(ex, 200, Jsons.obj(
                    "token", sess.token(),
                    "username", sess.username(),
                    "displayName", sess.displayName(),
                    "role", sess.role(),
                    "expiresAt", sess.expiresAt().toString()));
            return true;
        }
        if ("/api/auth/me".equals(path)) {
            AuthService.Session s0 = s.currentSession(ex);
            if (s0 == null) {
                s.send(ex, 401, err("未登录"));
                return true;
            }
            s.send(ex, 200, Jsons.obj(
                    "username", s0.username(),
                    "displayName", s0.displayName(),
                    "role", s0.role(),
                    "expiresAt", s0.expiresAt().toString()));
            return true;
        }
        if ("/api/auth/logout".equals(path)) {
            s.auth.logout(bearer(ex));
            s.send(ex, 200, Jsons.obj("ok", true));
            return true;
        }


        return false;
    }
}
