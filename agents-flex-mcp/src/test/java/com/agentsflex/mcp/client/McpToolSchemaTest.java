package com.agentsflex.mcp.client;

import com.agentsflex.core.model.chat.tool.Parameter;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MCP 工具声明的 inputSchema 是合法 JSON Schema，{@code type} 可以是字符串数组、
 * {@code enum} 可以是非字符串标量。这些形态都必须能转换成 {@link Parameter}。
 */
class McpToolSchemaTest {

    private static final String PROPERTY_NAME = "level";

    @Test
    @DisplayName("numeric enum values are exposed as strings instead of failing the whole tool")
    void numericEnumValuesAreConverted() {
        Parameter parameter = onlyParameter(property("integer", Arrays.asList(1, 2, 3)));

        assertEquals("integer", parameter.getType());
        assertArrayEquals(new String[]{"1", "2", "3"}, parameter.getEnums());
    }

    @Test
    @DisplayName("a nullable union type uses the non-null branch")
    void nullableUnionTypeUsesTheConcreteType() {
        Parameter parameter = onlyParameter(property(Arrays.asList("string", "null"), null));

        assertEquals("string", parameter.getType());
    }

    @Test
    @DisplayName("plain string type and string enum keep working")
    void stringTypeAndStringEnumAreUnchanged() {
        Parameter parameter = onlyParameter(property("string", Arrays.asList("a", "b")));

        assertEquals("string", parameter.getType());
        assertArrayEquals(new String[]{"a", "b"}, parameter.getEnums());
    }

    @Test
    @DisplayName("a property without type does not fail")
    void missingTypeIsTolerated() {
        Parameter parameter = onlyParameter(property(null, null));

        assertNull(parameter.getType());
        assertNull(parameter.getEnums());
    }

    private static Parameter onlyParameter(Map<String, Object> property) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PROPERTY_NAME, property);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);

        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("mcp-tool-schema")
            .description("schema conversion")
            .inputSchema(schema)
            .build();

        Parameter[] parameters = new McpTool(null, tool).getParameters();
        assertEquals(1, parameters.length);
        assertEquals(PROPERTY_NAME, parameters[0].getName());
        return parameters[0];
    }

    private static Map<String, Object> property(Object type, Object enumValues) {
        Map<String, Object> property = new LinkedHashMap<>();
        if (type != null) {
            property.put("type", type);
        }
        if (enumValues != null) {
            property.put("enum", enumValues);
        }
        property.put("description", "how deep to go");
        return property;
    }
}
