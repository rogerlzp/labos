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

    /** 规则 4 / 10 的错误码：DB 侧校验在 WorkflowService 完成，错误码必须与设计 §3.3b 一致。 */
    public static final String CODE_DICT_UNKNOWN = "ME_DICT_UNKNOWN";
    public static final String CODE_NODE_KIND_INVALID = "ME_NODE_KIND_INVALID";

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

        // 规则 2（§3.3b 字面）：target 必填且 plateId/barcode 至少一项。
        // 位置只是空间属性、不是身份（§3.3a 规则 1），只给 location 不足以过预检。
        JsonNode target = n.path("target");
        boolean hasIdentity = target.path("plateId").isNumber()
                || (target.path("barcode").isTextual()
                    && !target.path("barcode").asText().isBlank());
        if (!hasIdentity) {
            issues.add(new Issue("ME_TARGET_MISSING", "ERROR",
                    "transition 的 target 必须含 plateId 或 barcode（位置不是身份）"));
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

        // 规则 3：set 至少含 processState 或 sealState（不能拿无关字段充数）
        JsonNode set = n.path("set");
        if (!set.has("processState") && !set.has("sealState")) {
            issues.add(new Issue("ME_SET_EMPTY", "ERROR",
                    "transition 的 set 必须含 processState 或 sealState"));
        } else {
            // 规则 5：sealState ∈ {none, sealed}
            if (set.has("sealState") && !SEAL_STATES.contains(set.path("sealState").asText())) {
                issues.add(new Issue("ME_SEAL_INVALID", "ERROR",
                        "sealState 非法：" + set.path("sealState").asText()));
            }
        }

        // 规则 6：单 Effect —— outputs/operations 合计不得超过一组
        int declaredEffects = 0;
        if (n.path("outputs").isArray()) {
            declaredEffects += n.path("outputs").size();
        }
        if (n.path("operations").isArray()) {
            declaredEffects += n.path("operations").size();
        }
        if (declaredEffects > 1) {
            issues.add(new Issue("ME_MULTI_EFFECT", "ERROR",
                    "第一版单节点单圈只允许一个 Effect（outputs/operations 合计 " + declaredEffects + "）"));
        }

        // 规则 7：acceptedEvidenceLevels 白名单 + 存在但非数组也要报错
        JsonNode levels = n.path("acceptedEvidenceLevels");
        if (n.has("acceptedEvidenceLevels") && !levels.isArray()) {
            issues.add(new Issue("ME_EVIDENCE_LEVEL_INVALID", "ERROR",
                    "acceptedEvidenceLevels 必须是数组"));
        } else if (levels.isArray()) {
            for (JsonNode e : levels) {
                if (!EVIDENCE_LEVELS.contains(e.asText())) {
                    issues.add(new Issue("ME_EVIDENCE_LEVEL_INVALID", "ERROR",
                            "证据等级非法：" + e.asText()));
                }
                // 规则 8：声明只能「要求更高」，不能「授权人工确认」⇒ WARN 放行但要求人工复核
                if ("manual_confirmed".equals(e.asText())) {
                    issues.add(new Issue("ME_EVIDENCE_OVERRIDE", "WARN",
                            "声明含 manual_confirmed 不等于授权人工确认，需独立权限与人工复核（§3.3b 规则 8）"));
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
