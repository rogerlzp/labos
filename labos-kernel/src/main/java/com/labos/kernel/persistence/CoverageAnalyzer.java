package com.labos.kernel.persistence;

import com.labos.kernel.recovery.ErrorContext;
import com.labos.kernel.recovery.RecoveryPolicy;
import com.labos.kernel.recovery.RecoveryPolicyResolver;
import com.labos.kernel.recovery.RecoveryScope;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 策略覆盖率分析：拿真实历史错误分布，验证现有策略能兜住多少。
 *
 * <p>核心指标是<b>按报错次数加权</b>的覆盖率，不是按错误码个数——
 * 因为长尾错误码可能有 50 个，但总量只占 15%。
 */
public final class CoverageAnalyzer {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CoverageAnalyzer.class);
    /** 兼容原有 System.out.printf 的格式化输出。 */
    private static void printf(String format, Object... args) {
        log.info(String.format(format, args));
    }

    record CodeStat(String code, int occurrences, String topShooting) {
    }

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();
        List<CodeStat> codes = loadCodes(db);
        JdbcRecoveryPolicyRepository repo = new JdbcRecoveryPolicyRepository(db, 1L);
        List<RecoveryPolicy> policies = repo.loadAll();
        RecoveryPolicyResolver resolver = new RecoveryPolicyResolver(policies);

        long totalOcc = codes.stream().mapToLong(CodeStat::occurrences).sum();
        log.info("=".repeat(88));
        log.info("  策略覆盖率分析");
        log.info("=".repeat(88));
        printf("  错误码 %d 个，历史报错 %d 次，策略 %d 条%n%n",
                codes.size(), totalOcc, policies.size());

        for (RecoveryScope scope : new RecoveryScope[]{RecoveryScope.NODE, RecoveryScope.COMMAND}) {
            analyze(scope, codes, resolver, totalOcc);
        }

        log.info("=".repeat(88));
        log.info("  结论");
        log.info("=".repeat(88));
        log.info("  · 精确覆盖 = 该错误码有专门配置的策略");
        log.info("  · 通配兜底 = 只命中 `*` 策略（行为一刀切，无针对性）");
        log.info("  · 无匹配   = 引擎会直接转人工");
    }

    private static void analyze(RecoveryScope scope, List<CodeStat> codes,
                                RecoveryPolicyResolver resolver, long totalOcc) {
        int exactCodes = 0, wildCodes = 0, noneCodes = 0;
        long exactOcc = 0, wildOcc = 0, noneOcc = 0;
        List<String[]> uncovered = new ArrayList<>();

        for (CodeStat cs : codes) {
            ErrorContext ctx = ErrorContext.builder(cs.code(), scope).build();
            Optional<RecoveryPolicy> p = resolver.resolve(ctx);

            if (p.isEmpty()) {
                noneCodes++;
                noneOcc += cs.occurrences();
                uncovered.add(new String[]{cs.code(), String.valueOf(cs.occurrences()),
                        cs.topShooting(), "无匹配"});
            } else if (p.get().errorCode() == null) {
                wildCodes++;
                wildOcc += cs.occurrences();
                uncovered.add(new String[]{cs.code(), String.valueOf(cs.occurrences()),
                        cs.topShooting(), "仅通配(" + p.get().strategy() + ")"});
            } else {
                exactCodes++;
                exactOcc += cs.occurrences();
            }
        }

        log.info("─".repeat(88));
        printf("  作用域 %s%n", scope);
        log.info("─".repeat(88));
        printf("  %-14s %10s %10s %10s%n", "", "错误码数", "占比", "报错次数占比");
        row("精确覆盖", exactCodes, codes.size(), exactOcc, totalOcc);
        row("仅通配兜底", wildCodes, codes.size(), wildOcc, totalOcc);
        row("完全无匹配", noneCodes, codes.size(), noneOcc, totalOcc);
        log.info("");

        if (!uncovered.isEmpty()) {
            uncovered.sort(Comparator.comparingInt(a -> -Integer.parseInt(a[1])));
            printf("  未被精确覆盖的高频错误码（Top 10）：%n");
            printf("    %-18s %8s %-22s %s%n", "错误码", "次数", "历史主要处置", "当前匹配");
            log.info("    " + "-".repeat(76));
            for (int i = 0; i < Math.min(10, uncovered.size()); i++) {
                String[] u = uncovered.get(i);
                printf("    %-18s %8s %-22s %s%n", u[0], u[1], u[2], u[3]);
            }
            log.info("");
        }
    }

    private static void row(String label, int n, int totalN, long occ, long totalOcc) {
        printf("  %-14s %10d %9.1f%% %12.1f%%%n",
                label, n, totalN == 0 ? 0 : n * 100.0 / totalN,
                totalOcc == 0 ? 0 : occ * 100.0 / totalOcc);
    }

    private static List<CodeStat> loadCodes(DbConfig db) throws SQLException {
        String sql = """
                SELECT code,
                       COALESCE((attr->>'occurrences')::int, 0) AS occ,
                       COALESCE(attr->>'top_shooting', '-')     AS shoot
                FROM error_code
                WHERE tenant_id = 1 AND deleted_at IS NULL
                ORDER BY occ DESC
                """;
        List<CodeStat> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new CodeStat(rs.getString("code"), rs.getInt("occ"), rs.getString("shoot")));
            }
        }
        return out;
    }

    private CoverageAnalyzer() {
    }
}
