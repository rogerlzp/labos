package com.labos.kernel.workflow.runtime.graph;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DeterministicConditionEvaluatorTest {
    private final DeterministicConditionEvaluator evaluator = new DeterministicConditionEvaluator();

    @Test void evaluatesPrecedenceNestedPathsAndNumericValues() {
        Map<String,Object> vars = Map.of("sample", Map.of("volume", new BigDecimal("10.0"), "ready", true),
                "mode", "AUTO");
        assertTrue(evaluator.evaluate("sample.ready && sample.volume >= 10 && mode == 'AUTO'", vars));
        assertTrue(evaluator.evaluate("sample.volume > 11 || (sample.ready && mode != 'MANUAL')", vars));
    }

    @Test void evaluatesBooleanAndNullLiterals() {
        Map<String,Object> vars = new HashMap<>();
        vars.put("x", null);
        vars.put("enabled", true);
        assertTrue(evaluator.evaluate("x == null && enabled", vars));
        vars.put("x", "value");
        assertFalse(evaluator.evaluate("x == null", vars));
        assertTrue(evaluator.evaluate("!(x == null) && enabled", vars));
    }

    @Test void rejectsUnknownVariablesAndTypeCoercion() {
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("missing == 1", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("x == 1", Map.of("x", "1")));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("1", Map.of()));
    }

    @Test void rejectsCallsArithmeticAndMalformedExpressions() {
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("system.exit()", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("1 + 2 > 2", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("x &&", Map.of("x", true)));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate("x = 1", Map.of("x", 1)));
    }
}
