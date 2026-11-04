package com.labos.kernel.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 型号库元数据 纯逻辑自测（不连库）。
 *
 * <p>锁住 M10 先行接口 {@code GET /api/meta/device-models} 的数据基础
 * （{@link DeviceModels#libraryJson()}）与"哪些型号能对接真机 / 哪些只是占位"的口径：
 * <ol>
 *   <li><b>已适配（manual）</b>：只有 {@code aspin-2k} / {@code s100}，且必须是 MANUAL
 *       依据、手册路径非空、指令/错误码非空 —— 这些是能对客户说"能直接接"的型号；</li>
 *   <li><b>占位（planned）</b>：恰好 6 条；只有 360R / PlateLoc 能对上生产库
 *       {@code device.model} 写法（用于统计产线在跑台数），其余必须为 null，
 *       防止"瞎猜别名"把台数算到别的设备头上；</li>
 *   <li><b>JSON 形状</b>：totals / manual / planned 三块与字段名固定，
 *       前端和评审材料直接消费，改字段等于改契约；</li>
 *   <li><b>部署台数叠加</b>：{@link DeviceModels#libraryJson(Map)} 是 API 层查库后
 *       叠加 deployedCount 的纯逻辑部分，这里不连库也能钉住它的算法。</li>
 * </ol>
 */
public final class DeviceModelLibrarySelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        String json = DeviceModels.libraryJson();

        section("1. 已适配（manual）型号集");
        List<String> manualIds = new ArrayList<>(DeviceModels.knownIds());
        java.util.Collections.sort(manualIds);
        eq("manual 型号 id 恰好 {aspin-2k, s100}", List.of("aspin-2k", "s100"), manualIds);
        eq("totals.manual = TABLE 条数", 2, DeviceModels.all().size());

        section("2. TABLE 全部 MANUAL 且手册路径非空");
        List<String> notManual = new ArrayList<>();
        List<String> noManualPath = new ArrayList<>();
        for (DeviceModel m : DeviceModels.all()) {
            if (m.evidence != DeviceModel.Evidence.MANUAL) {
                notManual.add(m.id + "=" + m.evidence);
            }
            if (m.manual == null || m.manual.isBlank()) {
                noManualPath.add(m.id);
            }
        }
        eq("全部 evidence=MANUAL", List.of(), notManual);
        eq("全部 manual 路径非空", List.of(), noManualPath);

        section("3. 待适配（planned）清单：6 条 + 生产库别名");
        List<String> plannedIds = new ArrayList<>();
        for (DeviceModels.Planned p : DeviceModels.planned()) {
            plannedIds.add(p.id());
        }
        eq("planned 恰好 6 条、顺序固定", List.of(
                "hettich-rotanta-460r", "agilent-placeloc", "eppendorf-5810r",
                "thermo-multifuge-x1r", "beckman-allegra-x15r",
                "domestic-centrifuge-generic"), plannedIds);
        List<String> aliases = new ArrayList<>();
        for (DeviceModels.Planned p : DeviceModels.planned()) {
            aliases.add(p.dbModelAlias());
        }
        eq("只有 360R / PlateLoc 有库别名，其余为 null",
                Arrays.asList("360R", "PlateLoc", null, null, null, null), aliases);

        section("4. 每个 manual 型号的元数据（决定能否对客户承诺可对接）");
        eq("每个 manual 型号 methodCount > 0", List.of(), failingManual(
                m -> m.methods().size() > 0));
        eq("每个 manual 型号 documentedMethodCount > 0", List.of(), failingManual(m -> {
            int documented = 0;
            for (DeviceModel.Method mm : m.methods()) {
                if (mm.documented()) {
                    documented++;
                }
            }
            return documented > 0;
        }));
        eq("每个 manual 型号 errorCount > 0", List.of(), failingManual(
                m -> m.errorTable().size() > 0));
        eq("错误码块：aspin=0x41 / s100=0x40",
                List.of(0x41, 0x40),
                List.of(DeviceModels.of("aspin-2k").errorBlock,
                        DeviceModels.of("s100").errorBlock));

        section("5. 纯逻辑 JSON 形状（/api/meta/device-models 的基础）");
        check("含 totals / manual / planned 三块",
                json.contains("\"totals\":{") && json.contains("\"manual\":[")
                        && json.contains("\"planned\":["));
        check("totals 数值固定", json.contains("\"totals\":{\"manual\":2,\"planned\":6}"));
        eq("manual 数组 2 条", 2, countOccurrences(json, "\"evidence\":\"MANUAL\""));
        eq("planned 数组 6 条且都带 dbModelAlias", 6,
                countOccurrences(json, "\"dbModelAlias\":"));
        check("360R / PlateLoc 别名正确、其余 4 条 null",
                json.contains("\"dbModelAlias\":\"360R\"")
                        && json.contains("\"dbModelAlias\":\"PlateLoc\"")
                        && countOccurrences(json, "\"dbModelAlias\":null") == 4);
        check("manual 条目字段齐全（含 documented/unverified/errorBlock/note）",
                json.contains("\"id\":\"aspin-2k\",\"type\":\"centrifuge\","
                        + "\"vendor\":\"上海爱翱生物科技\",\"model\":\"ASpin 2K\","
                        + "\"protocol\":\"xml-dll\"")
                        && json.contains("\"manual\":\"docs/设备/")
                        && json.contains("\"doorCount\":")
                        && json.contains("\"actionTimeoutMs\":")
                        && countOccurrences(json, "\"documentedMethodCount\":") == 2
                        && countOccurrences(json, "\"unverifiedMethodCount\":") == 2
                        && countOccurrences(json, "\"errorCount\":") == 2
                        && countOccurrences(json, "\"errorBlock\":") == 2
                        && countOccurrences(json, "\"note\":") == 2);
        // 每个 manual 型号的 documented/unverified 数值必须与词表逐条一致
        // （unverifiedMethodCount = methodCount - documentedMethodCount）
        List<String> countMismatch = new ArrayList<>();
        for (DeviceModel m : DeviceModels.all()) {
            int documented = 0;
            for (DeviceModel.Method mm : m.methods()) {
                if (mm.documented()) {
                    documented++;
                }
            }
            String fragment = "\"methodCount\":" + m.methods().size()
                    + ",\"documentedMethodCount\":" + documented
                    + ",\"unverifiedMethodCount\":" + (m.methods().size() - documented);
            if (!json.contains(fragment)) {
                countMismatch.add(m.id);
            }
        }
        eq("manual 的 methodCount/documented/unverified 数值与词表一致",
                List.of(), countMismatch);
        check("纯逻辑口径不含 deployedCount（部署台数由 API 叠加）",
                !json.contains("deployedCount") && !json.contains("plannedDeployed"));

        section("6. 部署台数叠加（API 查库后的纯逻辑部分）");
        String withDeployed = DeviceModels.libraryJson(Map.of("360R", 1, "PlateLoc", 2));
        check("plannedDeployed = 1 + 2 = 3", withDeployed.contains("\"plannedDeployed\":3"));
        check("360R deployedCount=1 / PlateLoc deployedCount=2",
                withDeployed.contains("\"dbModelAlias\":\"360R\",\"deployedCount\":1")
                        && withDeployed.contains(
                                "\"dbModelAlias\":\"PlateLoc\",\"deployedCount\":2"));
        eq("其余 4 条占位 deployedCount=0", 4,
                countOccurrences(withDeployed, "\"deployedCount\":0"));
        eq("deployedCount 只出现在 planned 的 6 条里", 6,
                countOccurrences(withDeployed, "\"deployedCount\":"));

        System.out.printf("%n通过 %d, 失败 %d%n", pass, fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 辅助

    /** 返回不满足条件的 manual 型号 id（按词表顺序）。 */
    private static List<String> failingManual(java.util.function.Predicate<DeviceModel> rule) {
        List<String> out = new ArrayList<>();
        for (DeviceModel m : DeviceModels.all()) {
            if (m.evidence != DeviceModel.Evidence.MANUAL || !rule.test(m)) {
                out.add(m.id);
            }
        }
        return out;
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private static void eq(String name, Object expect, Object got) {
        if (Objects.equals(expect, got)) {
            pass++;
            System.out.printf("  ✓ %s%n", name);
        } else {
            fail++;
            System.out.printf("  ✗ %s（期望 %s，实际 %s）%n", name, expect, got);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.printf("  ✓ %s%n", name);
        } else {
            fail++;
            System.out.printf("  ✗ %s%n", name);
        }
    }

    private DeviceModelLibrarySelfTest() {
    }
}
