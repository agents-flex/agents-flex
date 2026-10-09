package com.agentsflex.graph.extractor.validation;

import com.agentsflex.graph.schema.GraphPropertyMetadata;
import com.agentsflex.graph.schema.GraphSchema;
import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 共享属性校验的类型边界与结构化诊断测试，防止抽取和人工修改采用不同规则。
 */
public class GraphSchemaPropertyValidatorTest {
    /**
     * 每种可移植类型均覆盖合法 Java 值和错误值，不允许隐式字符串转换。
     */
    @Test
    public void shouldEnforcePortablePropertyTypes() {
        Object[][] cases = {
            {GraphSchema.PropertyType.STRING, "林默", true},
            {GraphSchema.PropertyType.STRING, 1L, false},
            {GraphSchema.PropertyType.BOOLEAN, true, true},
            {GraphSchema.PropertyType.BOOLEAN, "true", false},
            {GraphSchema.PropertyType.INT64, (byte) 1, true},
            {GraphSchema.PropertyType.INT64, (short) 1, true},
            {GraphSchema.PropertyType.INT64, 1, true},
            {GraphSchema.PropertyType.INT64, Long.MAX_VALUE, true},
            {GraphSchema.PropertyType.INT64, 1D, false},
            {GraphSchema.PropertyType.INT64, "1", false},
            {GraphSchema.PropertyType.DOUBLE, 1L, true},
            {GraphSchema.PropertyType.DOUBLE, new BigDecimal("1.25"), true},
            {GraphSchema.PropertyType.DOUBLE, Double.NaN, false},
            {GraphSchema.PropertyType.DOUBLE, Double.POSITIVE_INFINITY, false},
            {GraphSchema.PropertyType.DOUBLE, Double.NEGATIVE_INFINITY, false},
            {GraphSchema.PropertyType.DOUBLE, "1.25", false},
            {GraphSchema.PropertyType.DATE, LocalDate.of(2024, 2, 29), true},
            {GraphSchema.PropertyType.DATE, "2024-02-29", true},
            {GraphSchema.PropertyType.DATE, "2023-02-29", false},
            {GraphSchema.PropertyType.DATE, "2024/02/29", false},
            {GraphSchema.PropertyType.DATE, 20240229L, false},
            {GraphSchema.PropertyType.DATETIME, LocalDateTime.of(2024, 2, 29, 8, 30), true},
            {GraphSchema.PropertyType.DATETIME, "2024-02-29T08:30:00", true},
            {GraphSchema.PropertyType.DATETIME, "2024-02-29T08:30:00Z", false},
            {GraphSchema.PropertyType.DATETIME, "2023-02-29T08:30:00", false},
            {GraphSchema.PropertyType.DATETIME, "2024-02-29", false},
            {GraphSchema.PropertyType.DATETIME, 1L, false}
        };
        for (Object[] item : cases) {
            GraphSchema.PropertyType type = (GraphSchema.PropertyType) item[0];
            GraphPropertyViolation violation = validate(item[1], new GraphSchema.Property("value", type, false));
            if ((Boolean) item[2]) assertNull(type + ": " + item[1], violation);
            else assertViolation(violation, "INVALID_PROPERTY_TYPE", "value");
        }
    }

    /**
     * 空值只允许出现在非必填字段；缺失和显式 null 都应定位到必填字段。
     */
    @Test
    public void shouldDistinguishOptionalAndRequiredProperties() {
        assertNull(GraphSchemaPropertyValidator.findViolation(null, null));
        for (GraphSchema.PropertyType type : GraphSchema.PropertyType.values()) {
            GraphSchema.Property optional = new GraphSchema.Property("value", type, false);
            assertNull(validate(null, optional));
            assertNull(GraphSchemaPropertyValidator.findViolation(null, Collections.singletonList(optional)));
            GraphSchema.Property required = new GraphSchema.Property("value", type, true);
            assertViolation(validate(null, required), "MISSING_REQUIRED_PROPERTY", "value");
            assertViolation(GraphSchemaPropertyValidator.findViolation(null, Collections.singletonList(required)),
                "MISSING_REQUIRED_PROPERTY", "value");
        }
    }

    /**
     * 枚举检查在类型校验之后执行，允许可选字段为空，不允许未知值。
     */
    @Test
    public void shouldEnforceEnumWithoutCoercingTypes() {
        GraphSchema.Property status = new GraphSchema.Property("value", GraphSchema.PropertyType.STRING, false,
            new GraphPropertyMetadata("状态", "当前状态", null, Arrays.asList("ACTIVE", "INACTIVE")));
        assertNull(validate("ACTIVE", status));
        assertNull(validate(null, status));
        assertViolation(validate("UNKNOWN", status), "PROPERTY_ENUM_VIOLATION", "value");
        assertViolation(validate(1L, status), "INVALID_PROPERTY_TYPE", "value");
    }

    /**
     * 未定义属性（包括坏字段名）返回可诊断结果，不因坏候选中断整批处理。
     */
    @Test
    public void shouldReportUnknownPropertyWithOriginalKey() {
        for (String key : Arrays.asList("extra", "", null)) {
            GraphPropertyViolation violation = GraphSchemaPropertyValidator.findViolation(
                Collections.singletonMap(key, "x"), null);
            assertViolation(violation, "UNKNOWN_PROPERTY", key);
        }
    }

    /**
     * 属性按输入顺序报告首个问题，校验过程不得改写调用方数据。
     */
    @Test
    public void shouldReturnFirstViolationWithoutMutatingInput() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("value", "bad number");
        values.put("extra", 1L);
        Map<String, Object> snapshot = new LinkedHashMap<>(values);
        assertViolation(GraphSchemaPropertyValidator.findViolation(Collections.unmodifiableMap(values),
                Collections.singletonList(new GraphSchema.Property("value", GraphSchema.PropertyType.INT64, true))),
            "INVALID_PROPERTY_TYPE", "value");
        assertEquals(snapshot, values);
    }

    /**
     * 机器代码和诊断文案不能为空，避免 UI 和日志收到无法识别的问题。
     */
    @Test
    public void shouldRequireDiagnosticCodeAndMessage() {
        for (String blank : Arrays.asList(null, "", "  ")) {
            assertThrows(IllegalArgumentException.class, () -> new GraphPropertyViolation(blank, "value", "message"));
            assertThrows(IllegalArgumentException.class, () -> new GraphPropertyViolation("CODE", "value", blank));
        }
    }

    /**
     * 便于类型矩阵使用单字段 Schema。
     */
    private static GraphPropertyViolation validate(Object value, GraphSchema.Property definition) {
        return GraphSchemaPropertyValidator.findViolation(Collections.singletonMap("value", value),
            Collections.singletonList(definition));
    }

    /**
     * 验证调用方依赖的结构化定位信息和日志文案。
     */
    private static void assertViolation(GraphPropertyViolation violation, String code, String name) {
        assertNotNull(violation);
        assertEquals(code, violation.getCode());
        assertEquals(name, violation.getPropertyName());
        assertTrue(violation.getMessage().contains(String.valueOf(name)));
    }
}
