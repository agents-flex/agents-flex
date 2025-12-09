package com.agentsflex.core.message;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

/**
 * 测试 ToolCall 类的 getArgsMap 方法
 */
public class ToolCallTest {

    /**
     * 测试当 arguments 为 null 时，应该返回 null
     */
    @Test
    public void testGetArgsMap_ArgumentsIsNull_ReturnsNull() {
        ToolCall toolCall = new ToolCall();
//        toolCall.arguments = null;
        assertNull(toolCall.getArgsMap());
    }

    /**
     * 测试当 arguments 为空字符串时，应该返回 null
     */
    @Test
    public void testGetArgsMap_ArgumentsIsEmpty_ReturnsNull() {
        ToolCall toolCall = new ToolCall();
        toolCall.setArguments("");
        assertNull(toolCall.getArgsMap());
    }

    /**
     * 测试正常格式的 JSON 字符串能正确解析为 Map
     */
    @Test
    public void testGetArgsMap_ValidJsonString_ParsesSuccessfully() {
        ToolCall toolCall = new ToolCall();
        toolCall.setArguments("{\"name\":\"张三\", \"age\":25}");
        Map<String, Object> result = toolCall.getArgsMap();
        assertNotNull(result);
        assertEquals("张三", result.get("name"));
        assertEquals(25, result.get("age"));
    }

    /**
     * 测试带有前缀和后缀的内容能够提取核心 JSON 进行解析
     */
    @Test
    public void testGetArgsMap_WithPrefixSuffix_ExtractionWorks() {
        ToolCall toolCall = new ToolCall();
        toolCall.setArguments("some prefix {\"name\":\"李四\", \"score\":90} some suffix");
        Map<String, Object> result = toolCall.getArgsMap();
        assertNotNull(result);
        assertEquals("李四", result.get("name"));
        assertEquals(90, result.get("score"));
    }

    /**
     * 测试缺少起始大括号的情况，自动补充后应能解析
     */
    @Test
    public void testGetArgsMap_MissingStartBrace_AutoCompleteAndParse() {
        ToolCall toolCall = new ToolCall();
        toolCall.setArguments("\"name\":\"王五\", \"active\":true}");
        Map<String, Object> result = toolCall.getArgsMap();
        assertNotNull(result);
        assertEquals("王五", result.get("name"));
        assertTrue((Boolean) result.get("active"));
    }

    /**
     * 测试缺少结束大括号的情况，自动补充后应能解析
     */
    @Test
    public void testGetArgsMap_MissingEndBrace_AutoCompleteAndParse() {
        ToolCall toolCall = new ToolCall();
        toolCall.setArguments("{\"id\":123,\"status\":\"pending\"");
        Map<String, Object> result = toolCall.getArgsMap();
        assertNotNull(result);
        assertEquals(123, result.get("id"));
        assertEquals("pending", result.get("status"));
    }

    /**
     * 测试完全无效且不可修复的 JSON 应抛出异常或者返回 null（根据具体实现）
     * 注意：由于 parseObject 内部会 try-catch 处理，最终可能会返回 null 或者抛错，
     * 实际运行结果取决于 JSON 工具的行为，这里假设它不会崩溃而是返回 null。
     */
    @Test
    public void testGetArgsMap_CompletelyInvalidJson_ReturnsNull() {
        ToolCall toolCall = new ToolCall();
        toolCall.setArguments("this is not a json at all");
        // 假设最终无法解析，返回 null
        assertNull(toolCall.getArgsMap());
    }
}
