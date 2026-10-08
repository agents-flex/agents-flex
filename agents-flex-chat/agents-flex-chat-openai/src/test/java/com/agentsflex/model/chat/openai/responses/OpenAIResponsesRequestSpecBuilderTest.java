package com.agentsflex.model.chat.openai.responses;

import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OpenAIResponsesRequestSpecBuilderTest {

    @Test
    public void buildsResponsesInputAndFunctionTools() {
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.setSystemMessage("You are an order assistant.");
        prompt.addMessage(new UserMessage("查询订单 1001"));
        prompt.addTool(Tool.builder("query_order", "查询订单")
            .addParameter(Parameter.builder().name("id").type("string").required(true).build())
            .build());

        OpenAIResponsesChatConfig config = new OpenAIResponsesChatConfig();
        config.setModel("gpt-4.1-mini");
        String body = new OpenAIResponsesRequestSpecBuilder().buildRequestBody(
            prompt, new ChatOptions(), config, false);
        JSONObject json = JSON.parseObject(body);

        assertEquals("gpt-4.1-mini", json.getString("model"));
        assertEquals("system", json.getJSONArray("input").getJSONObject(0).getString("role"));
        assertEquals("user", json.getJSONArray("input").getJSONObject(1).getString("role"));
        assertEquals("function", json.getJSONArray("tools").getJSONObject(0).getString("type"));
        assertEquals("query_order", json.getJSONArray("tools").getJSONObject(0).getString("name"));
    }

    @Test
    public void serializesToolResultAsFunctionCallOutput() {
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addMessage(new UserMessage("查询订单"));
        ToolMessage result = new ToolMessage();
        result.setToolCallId("call_1");
        result.setContent("订单已支付");
        prompt.addMessage(result);

        JSONObject json = JSON.parseObject(new OpenAIResponsesRequestSpecBuilder().buildRequestBody(
            prompt, new ChatOptions(), new OpenAIResponsesChatConfig(), false));
        JSONObject output = json.getJSONArray("input").getJSONObject(1);
        assertEquals("function_call_output", output.getString("type"));
        assertEquals("call_1", output.getString("call_id"));
        assertEquals("订单已支付", output.getString("output"));
    }
}
