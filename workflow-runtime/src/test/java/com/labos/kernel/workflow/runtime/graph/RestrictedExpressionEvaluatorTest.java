package com.labos.kernel.workflow.runtime.graph;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RestrictedExpressionEvaluatorTest {
    @Test void evaluatesBooleanPrecedenceAndParentheses() {
        assertTrue(RestrictedExpressionEvaluator.evaluateBoolean("ready && (count >= 2 || override)",
                Map.of("ready", true, "count", 2, "override", false)));
        assertFalse(RestrictedExpressionEvaluator.evaluateBoolean("ready && count > 2 || override",
                Map.of("ready", true, "count", 2, "override", false)));
        assertTrue(RestrictedExpressionEvaluator.evaluateBoolean("!blocked && enabled",
                Map.of("blocked", false, "enabled", true)));
    }
    @Test void comparesNumbersWithoutFloatingPointCoercion() {
        assertTrue(RestrictedExpressionEvaluator.evaluateBoolean("amount == 1.00 && amount < limit",
                Map.of("amount", new BigDecimal("1.0"), "limit", 2)));
    }
    @Test void supportsStringsAndNullEquality() {
        Map<String,Object> vars = new HashMap<>();
        vars.put("status", "ready"); vars.put("optional", null);
        assertTrue(RestrictedExpressionEvaluator.evaluateBoolean("status == 'ready' && optional == null", vars));
        assertTrue(RestrictedExpressionEvaluator.evaluateBoolean("name == \"a\\\\\\\"b\"", Map.of("name", "a\\\"b")));
    }
    @Test void resolvesDottedVariableNamesByExactMapKey() {
        assertTrue(RestrictedExpressionEvaluator.evaluateBoolean("input.qualified == true", Map.of("input.qualified", true)));
    }
    @Test void rejectsUnknownVariablesAndNonBooleanRoot() {
        assertThrows(IllegalArgumentException.class, () -> RestrictedExpressionEvaluator.evaluateBoolean("missing == 1", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> RestrictedExpressionEvaluator.evaluateBoolean("count", Map.of("count", 1)));
    }
    @Test void rejectsTypeCoercionAndInvalidComparisons() {
        assertThrows(IllegalArgumentException.class, () -> RestrictedExpressionEvaluator.evaluateBoolean("flag == 1", Map.of("flag", true)));
        assertThrows(IllegalArgumentException.class, () -> RestrictedExpressionEvaluator.evaluateBoolean("name > 1", Map.of("name", "x")));
        assertThrows(IllegalArgumentException.class, () -> RestrictedExpressionEvaluator.evaluateBoolean("1 && true", Map.of()));
    }
    @Test void rejectsScriptingArithmeticAndMethodInvocation() {
        for (String e : new String[] {"1 + 2 == 3", "System.exit(0)", "value.toString() == 'x'",
                "value[0] == 1", "value = 1", "true ? false : true", "$" + "{flag}"}) {
            assertThrows(IllegalArgumentException.class, () -> RestrictedExpressionEvaluator.evaluateBoolean(e, Map.of()), e);
        }
    }
    @Test void rejectsMalformedAndBlankExpressions() {
        for (String e : new String[] {"", "  ", "true &&", "(true", "true)", "1..2 == 1"})
            assertThrows(IllegalArgumentException.class, () -> RestrictedExpressionEvaluator.evaluateBoolean(e, Map.of()), e);
    }
    @Test void validatesExpressionSyntaxWithoutResolvingVariables() {
        assertDoesNotThrow(() -> RestrictedExpressionEvaluator.validateSyntax("ready && (amount >= 2 || override)"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedExpressionEvaluator.validateSyntax("true &&"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedExpressionEvaluator.validateSyntax("System.exit(0)"));
    }
    @Test void booleanOperatorsShortCircuitVariableResolution() {
        assertTrue(RestrictedExpressionEvaluator.evaluateBoolean("true || missing", Map.of()));
        assertFalse(RestrictedExpressionEvaluator.evaluateBoolean("false && missing", Map.of()));
    }
}
