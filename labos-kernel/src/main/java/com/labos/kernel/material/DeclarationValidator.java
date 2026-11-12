package com.labos.kernel.material;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 声明契约预检（§3.3b 规则 1-10 里不依赖 DB 的部分）。
 *
 * <p>纯逻辑：只做 JSON 结构/取值校验。字典存在性（规则 4）与节点种类（规则 10）
 * 需要 DB/节点信息，由调用方补充。
 */
public final class DeclarationValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> MODES =
            Set.of("transition", "none", "legacy_unspecified");
    private static final Set<String> SEAL_STATES = Set.of("none", "sealed");
    private static final Set<String> EVIDENCE_LEVELS =
            Set.of("device_ack", "physical_confirmed", "manual_confirmed", "legacy");

    /** 一条预检问题。 */
    public record Issue(String code, String level, String message) {
    }

    private DeclarationValidator() {
    }

    /** 校验声明 JSON；返回问题列表，空 = 通过。 */
    public static List<Issue> validate(String declarationJson) {
        List<Issue> issues = new ArrayList<>();
        JsonNode n = parse(declarationJson);
        if (n == null) {
            issues.add(new Issue("ME_INVALID_JSON", "ERROR", "声明不是合法 JSON"));
            return issues;
        }

        // 规则 1：mode 白名单
        JsonNode modeNode = n.get("mode");
        String mode = modeNode != null && modeNode.isTextual() ? modeNode.asText() : null;
        if (!MODES.contains(mode)) {
            issues.add(new Issue("ME_INVALID_MODE", "ERROR", "mode 非法：" + mode));
            return issues;   // mode 非法，后续规则无意义
        }
        if (!"transition".equals(mode)) {
            return issues;   // none / legacy_unspecified：无 target/set 约束
        }

        // 规则 2：transition 必须含 target（plateId/barcode/location 至少一）
        JsonNode target = n.path("target");
        boolean hasTarget = (target.path("plateId").isNumber())
                || (target.path("barcode").isTextual() && !target.path("barcode").asText().isBlank())
                || !target.path("location").isMissingNode();
        if (!hasTarget) {
            issues.add(new Issue("ME_TARGET_MISSING", "ERROR", "transition 必须含 target"));
        }

        // 规则 9：第一版拒绝 AUTO- 占位 / scanRef
        if (target.path("barcode").isTextual()) {
            String barcode = target.path("barcode").asText();
            if (barcode.startsWith("AUTO-")) {
                issues.add(new Issue("ME_AUTO_UNSUPPORTED", "ERROR", "第一版不支持 AUTO 占位板"));
            }
        }
        if (n.path("target").has("scanRef")) {
            issues.add(new Issue("ME_SCANREF_UNSUPPORTED", "ERROR", "第一版不支持 scanRef（后置 D2）"));
        }

        // 规则 3：set 至少含 processState 或 sealState
        JsonNode set = n.path("set");
        if (set.isMissingNode() || set.size() == 0) {
            issues.add(new Issue("ME_SET_EMPTY", "ERROR", "transition 必须含 set"));
        } else {
            // 规则 5：sealState ∈ {none, sealed}
            if (set.has("sealState") && !SEAL_STATES.contains(set.path("sealState").asText())) {
                issues.add(new Issue("ME_SEAL_INVALID", "ERROR",
                        "sealState 非法：" + set.path("sealState").asText()));
            }
        }

        // 规则 7：acceptedEvidenceLevels 白名单
        if (n.has("acceptedEvidenceLevels") && n.path("acceptedEvidenceLevels").isArray()) {
            for (JsonNode e : n.path("acceptedEvidenceLevels")) {
                if (!EVIDENCE_LEVELS.contains(e.asText())) {
                    issues.add(new Issue("ME_EVIDENCE_LEVEL_INVALID", "ERROR",
                            "证据等级非法：" + e.asText()));
                }
            }
        }
        return issues;
    }

    /** 规则 10：声明只允许挂在设备 TASK 节点（node_type ∈ {task, script}，见 §3.7）。 */
    public static boolean taskNodeKind(String nodeType) {
        return "task".equals(nodeType) || "script".equals(nodeType);
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
