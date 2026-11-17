package com.labos.kernel.driver;

import com.labos.kernel.device.DeviceIds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 设备 **id 空间** 不变量 纯逻辑自测（不连库，2026-11-16）。
 *
 * <p><b>背景</b>：同一台设备在库里有三套 bigint id ——
 * 实例号 {@code device_instance.id}、资源号 {@code resource_node.id}、
 * 型号行号 {@code device.id}。三者类型相同 ⇒ 传错**编译能过、SQL 也不报错，只是查错行**。
 *
 * <p>实际事故：{@code DriverStepExecutor.resolveTimeoutMs} 把实例号传给
 * {@code device_method.device_id}（那列存型号行号）。结果是
 * <ol>
 *   <li>不撞号 ⇒ 0 行 ⇒ {@code COALESCE(MAX(..),0)} 吞成 0 ⇒ 静默退回能力默认；</li>
 *   <li>撞号 ⇒ 白捡别人的上限 —— DISP3（实例 1000039）撞上
 *       {@code GANTRY_ROBOT_GENERIC}（型号行 1000039）⇒ 分液被套 7 秒 ⇒
 *       {@code EC-05-1007} ⇒ 重试 3 次 ⇒ 转人工。</li>
 * </ol>
 *
 * <p><b>为什么用源码扫描而不是只写单测</b>：这类错误"改完之后再写一遍"随时会复发，
 * 而单测只覆盖它测到的那条路径。同 {@code ExperimentStateProjectorSelfTest} 的思路：
 * 把「结构约束」变成一条**会红的测试**。
 *
 * <p>钉死的九条：
 * <ol>
 *   <li>{@code device_method} 的 DML 里不得再出现 {@code device_id} 列（列已改名
 *       {@code device_model_id}）；</li>
 *   <li>{@code device_instance} 的 DML 里不得再出现 {@code device_id} 列；</li>
 *   <li><b>型号级</b>四条（{@code selectMethodTimeoutSec} / {@code selectLibraryTimeoutSec} /
 *       {@code selectMethodInterruptible} / {@code selectDeviceLockTimeoutSec}）必须用
 *       {@code device_model_id} 且限定 {@code scope='model'}；</li>
 *   <li><b>实例级</b>三条（{@code selectInstance*TimeoutSec} /
 *       {@code selectInstanceMethodInterruptible}）必须用 {@code device_instance_id}
 *       且限定 {@code scope='instance'}，并且**不许**出现 {@code device_model_id} —
 *       那是把事故换个方向重演（拿型号列冒充实例号）；</li>
 *   <li>Java 源码里不得把 {@code instanceId} 直接喂给型号语义的 Mapper 方法
 *       （{@code selectMethodTimeoutSec} / {@code selectLibraryTimeoutSec} /
 *       {@code selectMethodInterruptible} / {@code deviceMethodTimeoutSec} /
 *       {@code deviceLibraryTimeoutSec} / {@code selectDeviceLockTimeoutSec}）；</li>
 *   <li>Java 源码里不得自己拼「实例号 → 型号行号」的 SQL（换算只许在
 *       {@code selectModelIdOfInstance} 一处）；</li>
 *   <li>{@link DeviceIds} 的三套 id 是**互不相通**的类型（不同 class、equals 不跨类型）；</li>
 *   <li>换算确实在 SQL 层存在（{@code selectModelIdOfInstance} 且读 {@code device_model_id}）；</li>
 *   <li>{@link TimeoutDiagnostics} 的计数契约（落空要能被看见）。</li>
 * </ol>
 */
public final class DeviceIdSpaceSelfTest {

    private static int pass;
    private static int fail;

    /** 型号语义的 Mapper 方法：参数必须是型号行号，不能是实例号。 */
    private static final String[] MODEL_SEMANTIC_CALLS = {
            "selectMethodTimeoutSec",
            "selectLibraryTimeoutSec",
            "selectMethodInterruptible",
            "deviceMethodTimeoutSec",
            "deviceLibraryTimeoutSec",
            "selectDeviceLockTimeoutSec",
    };

    /** 型号级（scope='model'）：必须用 device_model_id，不得出现实例号语义。 */
    private static final String[] MODEL_LEVEL_STATEMENTS = {
            "selectMethodTimeoutSec",
            "selectLibraryTimeoutSec",
            "selectMethodInterruptible",
            "selectDeviceLockTimeoutSec",
    };

    /** 实例级（scope='instance'，2026-11-17）：必须用 device_instance_id。 */
    private static final String[] INSTANCE_LEVEL_STATEMENTS = {
            "selectInstanceMethodTimeoutSec",
            "selectInstanceLibraryTimeoutSec",
            "selectInstanceMethodInterruptible",
    };

    /** mapper 块切分：{@code <select|insert|update|delete ...> ... </同名>}。 */
    private static final Pattern STATEMENT = Pattern.compile(
            "<(select|insert|update|delete)\\b[^>]*>(.*?)</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 裸列名 {@code device_id}（前面不是点，排除 {@code rc.device_id} 这类带别名）。 */
    private static final Pattern BARE_DEVICE_ID = Pattern.compile("(?<![\\w.])device_id\\b");

    public static void main(String[] args) throws IOException {
        System.out.println("════ 设备 id 空间不变量 · 纯逻辑自测 ════");
        System.out.println();

        Path mainJava = findMainJavaRoot();
        if (mainJava == null) {
            System.out.println("✗ 找不到 src/main/java（请在 labos-kernel 或仓库根目录下运行）");
            System.exit(1);
            return;
        }
        Path mapperDir = findMapperDir(mainJava);
        List<Path> javaFiles = javaFiles(mainJava);
        List<Path> mapperXmls = mapXmls(mapperDir);

        section("0. 源码树可枚举");
        check("Java 源文件 > 50 个（实际 " + javaFiles.size() + "）", javaFiles.size() > 50);
        check("mapper XML > 10 个（实际 " + mapperXmls.size() + "）", mapperXmls.size() > 10);

        testMapperColumns(mapperXmls);
        testInstanceFacingStatements(mapperXmls);
        testJavaCallSites(javaFiles);
        testIdentityTypes();
        testConversionExists(mapperXmls);
        testTimeoutDiagnostics();

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 1/2. 列名

    private static void testMapperColumns(List<Path> mapperXmls) throws IOException {
        section("1. mapper 里 device_method / device_instance 不再出现 device_id 列");
        List<String> bad = new ArrayList<>();
        int methodStatements = 0;
        int instanceStatements = 0;

        for (Path p : mapperXmls) {
            Matcher m = STATEMENT.matcher(stripXmlComments(read(p)));
            while (m.find()) {
                String body = m.group(2);
                boolean touchesMethod = body.contains("device_method");
                boolean touchesInstance = body.contains("device_instance");
                if (!touchesMethod && !touchesInstance) {
                    continue;
                }
                if (touchesMethod) {
                    methodStatements++;
                }
                if (touchesInstance) {
                    instanceStatements++;
                }
                // 只查「裸列名」：rc.device_id / cl.device_id 属于 run_command / consumable_lot，
                // 不是本次改名的对象，不能误伤。
                if (BARE_DEVICE_ID.matcher(body).find()) {
                    bad.add(p.getFileName() + " 的语句 " + statementId(m.group(0))
                            + "（涉及 " + (touchesMethod ? "device_method" : "")
                            + (touchesMethod && touchesInstance ? "+" : "")
                            + (touchesInstance ? "device_instance" : "") + "）仍有裸 device_id");
                }
            }
        }

        check("扫到 device_method 相关语句 " + methodStatements + " 条（应 > 3）",
                methodStatements > 3);
        check("扫到 device_instance 相关语句 " + instanceStatements + " 条（应 > 5）",
                instanceStatements > 5);
        for (String b : bad) {
            System.out.println("      · " + b);
        }
        check("无残留 device_id 列（违规 " + bad.size() + " 处）", bad.isEmpty());
    }

    // ---------------------------------------------------------------- 3. 四条语句

    private static void testInstanceFacingStatements(List<Path> mapperXmls) throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path p : mapperXmls) {
            all.append(stripXmlComments(read(p))).append('\n');
        }
        String xml = all.toString();

        section("2. 型号级（scope='model'）四条必须用 device_model_id");
        for (String id : MODEL_LEVEL_STATEMENTS) {
            String block = statementById(xml, id);
            check(id + " 存在", block != null);
            if (block == null) {
                continue;
            }
            check(id + " 用 device_model_id", block.contains("device_model_id"));
            check(id + " 限定 scope='model'", block.contains("scope = 'model'"));
            check(id + " 不含裸 device_id", !BARE_DEVICE_ID.matcher(block).find());
        }

        section("3. 实例级（scope='instance'）三条必须用 device_instance_id");
        for (String id : INSTANCE_LEVEL_STATEMENTS) {
            String block = statementById(xml, id);
            check(id + " 存在", block != null);
            if (block == null) {
                continue;
            }
            check(id + " 用 device_instance_id", block.contains("device_instance_id"));
            check(id + " 限定 scope='instance'", block.contains("scope = 'instance'"));
            // 关键反向断言：不许用 device_model_id 冒充实例号 ——
            // 那正是 BUG-20261116-02 的形态换了个方向重演。
            check(id + " 不误用 device_model_id 当实例号", !block.contains("device_model_id"));
        }
    }

    // ---------------------------------------------------------------- 4/5. Java 调用点

    private static void testJavaCallSites(List<Path> javaFiles) throws IOException {
        section("4. Java 源码：不得把实例号喂给型号语义方法");
        List<String> bad = new ArrayList<>();
        for (Path p : javaFiles) {
            String src = stripJavaComments(read(p));
            for (String call : MODEL_SEMANTIC_CALLS) {
                // 形态：(... tenantId, instanceId ...) 或 (instanceId ...)
                Pattern pat = Pattern.compile(
                        "\\b" + call + "\\s*\\([^)]{0,80}?\\binstanceId\\b");
                if (pat.matcher(src).find()) {
                    bad.add(p.getFileName() + " → " + call + "(... instanceId ...)");
                }
                // 形态：传的是 ctx.resourceId()（那就是实例号）
                Pattern pat2 = Pattern.compile(
                        "\\b" + call + "\\s*\\([^)]{0,80}?resourceId\\s*\\(\\s*\\)");
                if (pat2.matcher(src).find()) {
                    bad.add(p.getFileName() + " → " + call + "(... ctx.resourceId() ...)");
                }
            }
        }
        for (String b : bad) {
            System.out.println("      · " + b);
        }
        check("无一处把实例号当型号用（违规 " + bad.size() + " 处）", bad.isEmpty());

        section("5. Java 源码：换算 SQL 只许在 mapper XML 里");
        List<String> inline = new ArrayList<>();
        for (Path p : javaFiles) {
            String src = stripJavaComments(read(p));
            // Java 里手写 "device_model_id FROM device_instance" ⇔ 再造一个换算入口
            if (Pattern.compile("device_model_id\\s+FROM\\s+device_instance",
                    Pattern.CASE_INSENSITIVE).matcher(src).find()) {
                inline.add(p.getFileName().toString());
            }
        }
        for (String b : inline) {
            System.out.println("      · " + b);
        }
        check("无 Java 内联换算 SQL（违规 " + inline.size() + " 处）", inline.isEmpty());
    }

    // ---------------------------------------------------------------- 6. 强类型

    private static void testIdentityTypes() {
        section("6. DeviceIds 三套 id 互不相通");
        DeviceIds.InstanceId i = DeviceIds.ofInstance(1L);
        DeviceIds.ResourceId r = DeviceIds.ofResource(1L);
        DeviceIds.ModelId m = DeviceIds.ofModel(1L);

        check("InstanceId / ResourceId / ModelId 是三个不同 class",
                !i.getClass().equals(r.getClass()) && !r.getClass().equals(m.getClass())
                        && !i.getClass().equals(m.getClass()));
        check("三者都是 record（父类 java.lang.Record）",
                "java.lang.Record".equals(i.getClass().getSuperclass().getName())
                        && "java.lang.Record".equals(r.getClass().getSuperclass().getName())
                        && "java.lang.Record".equals(m.getClass().getSuperclass().getName()));
        check("同值不同空间 equals 为 false",
                !i.equals(m) && !i.equals(r) && !r.equals(m));
        check("toString 带空间标签（排障一眼看出是哪套 id）",
                i.toString().startsWith("instance#")
                        && r.toString().startsWith("resource#")
                        && m.toString().startsWith("model#"));
        check("0 = 未知", !DeviceIds.ofModel(0L).known()
                && !DeviceIds.ofInstance(0L).known()
                && !DeviceIds.ofResource(0L).known());
        check("负数也算已知（自造测试数据用负数）", DeviceIds.ofModel(-1L).known());
        check("UNKNOWN_MODEL 的 value 是 0", DeviceIds.UNKNOWN_MODEL.value() == 0L);
    }

    // ---------------------------------------------------------------- 7. 换算存在

    private static void testConversionExists(List<Path> mapperXmls) throws IOException {
        section("7. 换算确实在 SQL 层（selectModelIdOfInstance）");
        StringBuilder all = new StringBuilder();
        for (Path p : mapperXmls) {
            all.append(stripXmlComments(read(p))).append('\n');
        }
        String block = statementById(all.toString(), "selectModelIdOfInstance");
        check("selectModelIdOfInstance 存在", block != null);
        if (block != null) {
            check("它从 device_instance 读 device_model_id",
                    block.contains("device_model_id") && block.contains("device_instance"));
        }
    }

    // ---------------------------------------------------------------- 7. 诊断计数

    /**
     * {@link TimeoutDiagnostics} 的计数契约。
     *
     * <p>为什么这条重要：事故的两个症状（"优先级链整条是死的"、"撞号白捡上限"）
     * 之所以两个月没人发现，就是因为**落空返回 0 是合法值**。
     * 诊断计数把这个"看不见的 0"变成可读的指标：
     * {@code deviceLevelMissing} 持续增长 = 方法库没给该型号配超时。
     */
    private static void testTimeoutDiagnostics() {
        section("8. 超时诊断计数（落空不再静默）");
        TimeoutDiagnostics.reset();

        TimeoutDiagnostics.recordModel(true);
        TimeoutDiagnostics.recordModel(false);
        TimeoutDiagnostics.recordSource("device_method");
        TimeoutDiagnostics.recordSource("device_method");
        TimeoutDiagnostics.recordSource("capability_default");
        TimeoutDiagnostics.recordDeviceLevelMissing();

        java.util.Map<String, Object> snap = TimeoutDiagnostics.snapshot();
        Object bySource = snap.get("bySource");
        check("snapshot 含 bySource", bySource instanceof java.util.Map);
        if (bySource instanceof java.util.Map<?, ?> m) {
            check("device_method 命中 2 次", Long.valueOf(2L).equals(m.get("device_method")));
            check("capability_default 命中 1 次",
                    Long.valueOf(1L).equals(m.get("capability_default")));
        }
        check("modelResolved = 1", Long.valueOf(1L).equals(snap.get("modelResolved")));
        check("modelUnresolved = 1", Long.valueOf(1L).equals(snap.get("modelUnresolved")));
        check("deviceLevelMissing = 1",
                Long.valueOf(1L).equals(snap.get("deviceLevelMissing")));

        check("warnOnce 同一 key 只放行一次",
                TimeoutDiagnostics.warnOnce("1000039|Run")
                        && !TimeoutDiagnostics.warnOnce("1000039|Run"));
        check("warnOnce 对不同 key 各放行一次",
                TimeoutDiagnostics.warnOnce("1000039|Home"));

        TimeoutDiagnostics.reset();
        check("reset 后计数清零",
                TimeoutDiagnostics.deviceLevelMissing() == 0L
                        && TimeoutDiagnostics.modelResolved() == 0L
                        && ((java.util.Map<?, ?>) TimeoutDiagnostics.snapshot().get("bySource"))
                        .isEmpty());
    }

    // ---------------------------------------------------------------- 工具

    private static String statementId(String statementXml) {
        Matcher m = Pattern.compile("<(?:select|insert|update|delete)\\b[^>]*\\bid\\s*=\\s*\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE).matcher(statementXml);
        return m.find() ? m.group(1) : "?";
    }

    /** 按语句 id 取出整个块（含标签），找不到返回 null。 */
    private static String statementById(String xml, String id) {
        Matcher m = STATEMENT.matcher(xml);
        while (m.find()) {
            if (id.equals(statementId(m.group(0)))) {
                return m.group(0);
            }
        }
        return null;
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String stripXmlComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", " ");
    }

    /**
     * 剥 Java 注释（行注释 / 块注释 / javadoc）。
     *
     * <p>为什么必须剥：本文件与 {@code DeviceIdentityResolver} 的注释里**大量引用**
     * 出错的调用形态做说明（"原来传的是 instanceId"）——不剥就把文档当违规。
     */
    private static String stripJavaComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    private static Path findMainJavaRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            Path cand = p.resolve("labos-kernel/src/main/java");
            if (Files.isDirectory(cand)) {
                return cand;
            }
            Path cand2 = p.resolve("src/main/java");
            if (Files.isDirectory(cand2) && Files.isDirectory(p.resolve("src/test/java"))) {
                return cand2;
            }
        }
        return null;
    }

    private static Path findMapperDir(Path srcRoot) {
        Path src = srcRoot.getParent() == null ? null : srcRoot.getParent().getParent();
        if (src == null) {
            return null;
        }
        Path dir = src.resolve("boot/resources/mapper");
        return Files.isDirectory(dir) ? dir : null;
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }

    private static List<Path> mapXmls(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (dir == null) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".xml"))
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }

    private static void section(String title) {
        System.out.println("── " + title + " ──");
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + label);
        } else {
            fail++;
            System.out.println("  ✗ " + label);
        }
    }
}
