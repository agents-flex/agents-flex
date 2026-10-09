package com.agentsflex.graph.extractor.validation;

import com.agentsflex.graph.schema.GraphSchema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 依据 Schema 属性定义校验属性映射的共享组件。
 *
 * <p>候选抽取和人工审核均使用本类，确保白名单、类型、枚举和必填规则一致。</p>
 */
public final class GraphSchemaPropertyValidator {
    /**
     * 工具类不允许实例化。
     */
    private GraphSchemaPropertyValidator() {
    }

    /**
     * 返回首个属性问题；没有问题时返回 {@code null}。
     *
     * @param values      待校验的完整属性映射；空值等价于空映射
     * @param definitions Schema 属性定义；空值等价于无定义
     * @return 首个结构化问题，或者 {@code null}
     */
    public static GraphPropertyViolation findViolation(Map<String, ?> values,
                                                       List<GraphSchema.Property> definitions) {
        Map<String, ?> safeValues = values == null ? Collections.<String, Object>emptyMap() : values;
        List<GraphSchema.Property> safeDefinitions = definitions == null
            ? Collections.<GraphSchema.Property>emptyList() : definitions;
        Map<String, GraphSchema.Property> properties = new HashMap<>();
        for (GraphSchema.Property property : safeDefinitions) properties.put(property.getName(), property);
        for (Map.Entry<String, ?> entry : safeValues.entrySet()) {
            GraphSchema.Property property = properties.get(entry.getKey());
            if (property == null) return violation("UNKNOWN_PROPERTY", entry.getKey(),
                "unknown property: " + entry.getKey());
            if (!matches(entry.getValue(), property.getType())) return violation("INVALID_PROPERTY_TYPE",
                entry.getKey(), "invalid value type for property: " + entry.getKey());
            if (!property.getMetadata().getEnumValues().isEmpty() && entry.getValue() != null
                && !property.getMetadata().getEnumValues().contains(String.valueOf(entry.getValue()))) {
                return violation("PROPERTY_ENUM_VIOLATION", entry.getKey(),
                    "value is not in the allowed enum for property: " + entry.getKey());
            }
        }
        for (GraphSchema.Property property : safeDefinitions) {
            if (property.isRequired() && (!safeValues.containsKey(property.getName())
                || safeValues.get(property.getName()) == null)) {
                return violation("MISSING_REQUIRED_PROPERTY", property.getName(),
                    "missing required property: " + property.getName());
            }
        }
        return null;
    }

    /**
     * 创建统一的属性问题对象。
     */
    private static GraphPropertyViolation violation(String code, String propertyName, String message) {
        return new GraphPropertyViolation(code, propertyName, message);
    }

    /**
     * 判断 Java 值是否符合可移植的图属性类型。
     */
    private static boolean matches(Object value, GraphSchema.PropertyType type) {
        if (value == null) return true;
        switch (type) {
            case STRING:
                return value instanceof String;
            case BOOLEAN:
                return value instanceof Boolean;
            case INT64:
                return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
            case DOUBLE:
                return finiteNumber(value);
            case DATE:
                return value instanceof LocalDate || parseDate(value);
            case DATETIME:
                return value instanceof LocalDateTime || parseDateTime(value);
            default:
                return false;
        }
    }

    /**
     * DOUBLE 接受 Number，但拒绝 NaN 和无穷值。
     */
    private static boolean finiteNumber(Object value) {
        if (!(value instanceof Number)) return false;
        double number = ((Number) value).doubleValue();
        return !Double.isNaN(number) && !Double.isInfinite(number);
    }

    /**
     * 解析严格的 ISO-8601 日期字符串。
     */
    private static boolean parseDate(Object value) {
        if (!(value instanceof String)) return false;
        try {
            LocalDate.parse((String) value);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    /**
     * 解析严格的 ISO-8601 本地日期时间字符串。
     */
    private static boolean parseDateTime(Object value) {
        if (!(value instanceof String)) return false;
        try {
            LocalDateTime.parse((String) value);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }
}
