package com.agentsflex.text2sql.tools;

import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolScanner;
import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * MathCalculatorTools - Mandatory Math Calculation Tool
 * <p>
 * This tool ensures that AI NEVER performs calculations by itself.
 * ALL mathematical operations MUST go through this tool to guarantee accuracy.
 * </p>
 */
public class MathCalculatorTools {

    private static final String RESULT_PREFIX = "[Calculator Result] ";
    private static final String WARNING_SUFFIX = "\n\nNote: This result is computed by the calculator tool. You MUST cite this exact value in your response.";

    // ========================================================================
    // Tool Builder
    // ========================================================================

    public List<Tool> buildTools() {
        return ToolScanner.scan(this);
    }

    // ========================================================================
    // Basic Arithmetic Operations
    // ========================================================================

    @ToolDef(
        name = "calculate",
        description = "" +
            "=== ⚠️ MANDATORY: ALL Math Calculations MUST Use This Tool ===\n\n" +

            "You are STRICTLY PROHIBITED from performing ANY mathematical calculations yourself.\n" +
            "This tool is the ONLY way to perform calculations. Violation is not allowed.\n\n" +

            "=== When to Use (MANDATORY) ===\n" +
            "- Addition, subtraction, multiplication, division\n" +
            "- Percentage calculations (e.g., 'what is 15% of 200?')\n" +
            "- Ratio calculations (e.g., 'what percentage is 30 out of 150?')\n" +
            "- Any arithmetic operations\n" +
            "- Converting between units that require math\n" +
            "- Comparing values that require subtraction\n\n" +

            "=== Supported Operations ===\n" +
            "- add: a + b\n" +
            "- subtract: a - b\n" +
            "- multiply: a * b\n" +
            "- divide: a / b\n" +
            "- percentage: calculate a% of b (e.g., 15% of 200)\n" +
            "- percentage_of: what percentage is a of b (e.g., 30 is what % of 150)\n" +
            "- percentage_change: percentage change from a to b\n" +
            "- power: a ^ b\n" +
            "- sqrt: square root of a\n" +
            "- abs: absolute value of a\n" +
            "- round: round a to b decimal places\n\n" +

            "=== Examples ===\n" +
            "- 'Calculate 123 + 456' → operation='add', a=123, b=456\n" +
            "- 'What is 15% of 200?' → operation='percentage', a=15, b=200\n" +
            "- '30 is what percentage of 150?' → operation='percentage_of', a=30, b=150\n" +
            "- 'Round 3.14159 to 2 decimal places' → operation='round', a=3.14159, b=2\n"
    )
    public String calculate(
        @ToolParam(name = "operation", description = "The operation to perform. Must be one of: " +
            "add, subtract, multiply, divide, percentage, percentage_of, percentage_change, power, sqrt, abs, round.\n" +
            "- add: a + b\n" +
            "- subtract: a - b\n" +
            "- multiply: a * b\n" +
            "- divide: a / b\n" +
            "- percentage: calculate a% of b (e.g., a=15, b=200 → 15% of 200 = 30)\n" +
            "- percentage_of: what percentage a is of b (e.g., a=30, b=150 → 30 is 20% of 150)\n" +
            "- percentage_change: percentage change from a to b (e.g., a=100, b=120 → 20% increase)\n" +
            "- power: a raised to the power of b\n" +
            "- sqrt: square root of a (b is ignored)\n" +
            "- abs: absolute value of a (b is ignored)\n" +
            "- round: round a to b decimal places\n") String operation,
        @ToolParam(name = "a", description = "First operand. For percentage operations, this is the percentage value or the part value.") Double a,
        @ToolParam(name = "b", description = "Second operand. For percentage operations, this is the base value. For sqrt/abs, this is ignored. For round, this is decimal places.") Double b
    ) {
        if (operation == null || operation.trim().isEmpty()) {
            return error("Operation cannot be empty");
        }
        if (a == null) {
            return error("Parameter 'a' cannot be null");
        }

        try {
            BigDecimal result;
            String expression;

            switch (operation.toLowerCase().trim()) {
                case "add":
                    if (b == null) return error("Parameter 'b' is required for add operation");
                    result = BigDecimal.valueOf(a).add(BigDecimal.valueOf(b));
                    expression = a + " + " + b + " = " + result;
                    break;

                case "subtract":
                    if (b == null) return error("Parameter 'b' is required for subtract operation");
                    result = BigDecimal.valueOf(a).subtract(BigDecimal.valueOf(b));
                    expression = a + " - " + b + " = " + result;
                    break;

                case "multiply":
                    if (b == null) return error("Parameter 'b' is required for multiply operation");
                    result = BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(b));
                    expression = a + " × " + b + " = " + result;
                    break;

                case "divide":
                    if (b == null) return error("Parameter 'b' is required for divide operation");
                    if (b == 0) return error("Division by zero is not allowed");
                    result = BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b), 10, RoundingMode.HALF_UP);
                    expression = a + " ÷ " + b + " = " + formatResult(result);
                    break;

                case "percentage":
                    // Calculate a% of b
                    if (b == null) return error("Parameter 'b' is required for percentage operation");
                    result = BigDecimal.valueOf(a)
                        .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(b));
                    expression = a + "% of " + b + " = " + formatResult(result);
                    break;

                case "percentage_of":
                    // Calculate what percentage a is of b
                    if (b == null) return error("Parameter 'b' is required for percentage_of operation");
                    if (b == 0) return error("Base value cannot be zero");
                    result = BigDecimal.valueOf(a)
                        .divide(BigDecimal.valueOf(b), 10, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                    expression = a + " is " + formatResult(result) + "% of " + b;
                    break;

                case "percentage_change":
                    // Calculate percentage change from a to b
                    if (b == null) return error("Parameter 'b' is required for percentage_change operation");
                    if (a == 0) return error("Original value cannot be zero");
                    result = BigDecimal.valueOf(b - a)
                        .divide(BigDecimal.valueOf(a), 10, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                    String direction = result.compareTo(BigDecimal.ZERO) >= 0 ? "increase" : "decrease";
                    expression = "Change from " + a + " to " + b + " = " + formatResult(result.abs()) + "% " + direction;
                    break;

                case "power":
                    if (b == null) return error("Parameter 'b' is required for power operation");
                    result = BigDecimal.valueOf(Math.pow(a, b));
                    expression = a + " ^ " + b + " = " + formatResult(result);
                    break;

                case "sqrt":
                    if (a < 0) return error("Cannot calculate square root of negative number");
                    result = BigDecimal.valueOf(Math.sqrt(a));
                    expression = "√" + a + " = " + formatResult(result);
                    break;

                case "abs":
                    result = BigDecimal.valueOf(Math.abs(a));
                    expression = "|" + a + "| = " + result;
                    break;

                case "round":
                    if (b == null) b = 0.0;
                    int decimals = b.intValue();
                    if (decimals < 0) decimals = 0;
                    result = BigDecimal.valueOf(a).setScale(decimals, RoundingMode.HALF_UP);
                    expression = "Round " + a + " to " + decimals + " decimal places = " + result;
                    break;

                default:
                    return error("Unknown operation: '" + operation + "'. Supported: add, subtract, multiply, divide, percentage, percentage_of, percentage_change, power, sqrt, abs, round");
            }

            return success(expression);

        } catch (Exception e) {
            return error("Calculation error: " + e.getMessage());
        }
    }

    // ========================================================================
    // Multi-value Operations
    // ========================================================================

    @ToolDef(
        name = "calculateAggregate",
        description = "" +
            "=== ⚠️ MANDATORY: Aggregate Calculations MUST Use This Tool ===\n\n" +

            "Use this tool when you need to calculate aggregate values from multiple numbers.\n" +
            "You are STRICTLY PROHIBITED from performing these calculations yourself.\n\n" +

            "=== Supported Operations ===\n" +
            "- sum: sum of all values\n" +
            "- avg: average of all values\n" +
            "- max: maximum value\n" +
            "- min: minimum value\n" +
            "- count: count of values\n" +
            "- range: difference between max and min\n\n" +

            "=== Example ===\n" +
            "- To sum 10, 20, 30: operation='sum', values=[10, 20, 30]\n"
    )
    public String calculateAggregate(
        @ToolParam(name = "operation", description = "The aggregate operation: sum, avg, max, min, count, range") String operation,
        @ToolParam(name = "values", description = "List of numeric values to aggregate") List<Double> values
    ) {
        if (operation == null || operation.trim().isEmpty()) {
            return error("Operation cannot be empty");
        }
        if (values == null || values.isEmpty()) {
            return error("Values list cannot be empty");
        }

        try {
            double result;
            String expression;

            switch (operation.toLowerCase().trim()) {
                case "sum":
                    result = values.stream().mapToDouble(Double::doubleValue).sum();
                    expression = "Sum of " + values + " = " + formatResult(BigDecimal.valueOf(result));
                    break;

                case "avg":
                    result = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    expression = "Average of " + values + " = " + formatResult(BigDecimal.valueOf(result));
                    break;

                case "max":
                    result = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                    expression = "Max of " + values + " = " + result;
                    break;

                case "min":
                    result = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                    expression = "Min of " + values + " = " + result;
                    break;

                case "count":
                    result = values.size();
                    expression = "Count of " + values + " = " + (int) result;
                    break;

                case "range":
                    double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                    double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                    result = max - min;
                    expression = "Range of " + values + " (max - min) = " + max + " - " + min + " = " + result;
                    break;

                default:
                    return error("Unknown aggregate operation: '" + operation + "'. Supported: sum, avg, max, min, count, range");
            }

            return success(expression);

        } catch (Exception e) {
            return error("Aggregate calculation error: " + e.getMessage());
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private String formatResult(BigDecimal value) {
        // Remove trailing zeros and return clean string
        return value.stripTrailingZeros().toPlainString();
    }

    private String success(String expression) {
        return RESULT_PREFIX + expression + WARNING_SUFFIX;
    }

    private String error(String message) {
        return "Calculator Error: " + message;
    }

    // ========================================================================
    // Builder Pattern
    // ========================================================================

    public static MathCalculatorTools create() {
        return new MathCalculatorTools();
    }
}
