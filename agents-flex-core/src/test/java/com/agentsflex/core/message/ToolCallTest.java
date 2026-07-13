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

    @Test
    public void testGetArgsMap_JavaScriptValues_ArePreservedAsStrings() {
        ToolCall toolCall = new ToolCall();
        toolCall.setArguments("{\"formatter\": function(params) { return params.map(function(p) { return p.value / 10000; }); },"
            + "\"date\": new Date(2025, 0, 1), \"pattern\": /foo[,}]bar/gi,"
            + "\"arrow\": (v) => ({ value: v }), \"missing\": undefined}");

        Map<String, Object> result = toolCall.getArgsMap();

        assertEquals("function(params) { return params.map(function(p) { return p.value / 10000; }); }", result.get("formatter"));
        assertEquals("new Date(2025, 0, 1)", result.get("date"));
        assertEquals("/foo[,}]bar/gi", result.get("pattern"));
        assertEquals("(v) => ({ value: v })", result.get("arrow"));
        assertEquals("undefined", result.get("missing"));
    }

    @Test
    public void testGetArgsMap_JavaScriptWordsInsideString_AreNotChanged() {
        ToolCall toolCall = new ToolCall();
        toolCall.setArguments("{\"code\":\"function() { return new Date(); }\",\"url\":\"/foo/gi\"}");

        Map<String, Object> result = toolCall.getArgsMap();

        assertEquals("function() { return new Date(); }", result.get("code"));
        assertEquals("/foo/gi", result.get("url"));
    }

    @Test
    public void testGetArgsMap_RegularExpression_IsPreservedAsString() {
        ToolCall toolCall = new ToolCall();
        toolCall.setArguments("{\"aaa\": /abc/g}");

        Map<String, Object> result = toolCall.getArgsMap();

        assertEquals("/abc/g", result.get("aaa"));
    }

    @Test
    public void testGetArgsMap_PrefixSuffixAndRegularExpression_ParsesSuccessfully() {
        ToolCall toolCall = new ToolCall();
        toolCall.setArguments("ignored prefix {not json} actual arguments: {\"aaa\": /abc{1,3}/g} ignored suffix");

        Map<String, Object> result = toolCall.getArgsMap();

        assertEquals("/abc{1,3}/g", result.get("aaa"));
    }

    @Test
    public void testGetArgsMap_MissingBracesAndJavaScriptValue_ParsesSuccessfully() {
        ToolCall toolCall = new ToolCall();
        toolCall.setArguments("\"value\": new Date()");

        Map<String, Object> result = toolCall.getArgsMap();

        assertEquals("new Date()", result.get("value"));
    }

    @Test
    public void testGetArgsMap_MissingEndBraceWithFunctionBody_ParsesSuccessfully() {
        ToolCall toolCall = new ToolCall();
        toolCall.setArguments("{\"value\": function() { return { nested: true }; }");

        Map<String, Object> result = toolCall.getArgsMap();

        assertEquals("function() { return { nested: true }; }", result.get("value"));
    }

    @Test
    public void testGetArgsMap_ComplexFunctionWithQuotesAndRegex_IsPreserved() {
        ToolCall toolCall = new ToolCall();
        String function = "function(params) { "
            + "var title = \"金额 \\\"汇总\\\"\"; "
            + "var message = `项目: ${params.name}`; "
            + "var config = { text: title, nested: { enabled: true } }; "
            + "return /[\\{\"}]/g.test(message) ? config : null; }";
        toolCall.setArguments("{\"formatter\": " + function + ", \"enabled\": true}");

        Map<String, Object> result = toolCall.getArgsMap();

        assertEquals(function, result.get("formatter"));
        assertEquals(true, result.get("enabled"));
    }

}
