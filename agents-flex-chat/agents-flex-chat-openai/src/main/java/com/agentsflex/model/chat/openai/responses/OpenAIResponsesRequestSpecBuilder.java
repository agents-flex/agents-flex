package com.agentsflex.model.chat.openai.responses;

import com.agentsflex.core.message.*;
import com.agentsflex.core.model.chat.BaseChatConfig;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.client.ChatMessageSerializer;
import com.agentsflex.core.model.client.ChatRequestSpec;
import com.agentsflex.core.model.client.ChatRequestSpecBuilder;
import com.agentsflex.core.model.client.OpenAIChatMessageSerializer;
import com.agentsflex.core.prompt.Prompt;
import com.alibaba.fastjson2.JSON;

import java.util.*;

/** Builds the input/items shape used by the OpenAI Responses API. */
public class OpenAIResponsesRequestSpecBuilder implements ChatRequestSpecBuilder {
    private final ChatMessageSerializer serializer = new OpenAIChatMessageSerializer();

    @Override
    public ChatRequestSpec buildRequestSpec(Prompt prompt, ChatOptions options, BaseChatConfig config) {
        boolean retry = options.getRetryEnabledOrDefault(config.isRetryEnabled());
        return new ChatRequestSpec(config.getFullUrl(), headers(config),
            retry ? options.getRetryCountOrDefault(config.getRetryCount()) : 0,
            retry ? options.getRetryInitialDelayMsOrDefault(config.getRetryInitialDelayMs()) : 0);
    }

    private Map<String, String> headers(BaseChatConfig config) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        return headers;
    }

    @Override
    public String buildRequestBody(Prompt prompt, ChatOptions options, BaseChatConfig config, boolean streaming) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", options.getModelOrDefault(config.getModel()));
        body.put("input", input(prompt, config));
        if (streaming) body.put("stream", true);
        if (options.getMaxTokens() != null) body.put("max_output_tokens", options.getMaxTokens());
        if (options.getTemperature() != null) body.put("temperature", options.getTemperature());
        if (options.getTopP() != null) body.put("top_p", options.getTopP());
        if (options.getExtraBody() != null) body.putAll(options.getExtraBody());
        List<Map<String, Object>> tools = responsesTools(prompt, config);
        if (tools != null && !tools.isEmpty()) body.put("tools", tools);
        if (prompt.getToolChoice() != null && tools != null && !tools.isEmpty()) {
            body.put("tool_choice", prompt.getToolChoice());
        }
        return JSON.toJSONString(body);
    }

    private List<Map<String, Object>> responsesTools(Prompt prompt, BaseChatConfig config) {
        List<Map<String, Object>> source = serializer.serializeTools(prompt, config);
        if (source == null) return null;
        List<Map<String, Object>> result = new ArrayList<>(source.size());
        for (Map<String, Object> tool : source) {
            if (!"function".equals(tool.get("type"))) {
                result.add(tool);
                continue;
            }
            @SuppressWarnings("unchecked") Map<String, Object> function =
                (Map<String, Object>) tool.get("function");
            Map<String, Object> responseTool = new LinkedHashMap<>();
            responseTool.put("type", "function");
            if (function != null) {
                responseTool.put("name", function.get("name"));
                responseTool.put("description", function.get("description"));
                responseTool.put("parameters", function.get("parameters"));
            }
            result.add(responseTool);
        }
        return result;
    }

    private List<Map<String, Object>> input(Prompt prompt, BaseChatConfig config) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> messages = serializer.serializeMessages(prompt.getMessages(), config);
        if (messages == null) return result;
        for (Map<String, Object> message : messages) {
            String role = String.valueOf(message.get("role"));
            if ("tool".equals(role)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "function_call_output");
                item.put("call_id", message.get("tool_call_id"));
                item.put("output", message.get("content"));
                result.add(item);
            } else {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", role);
                item.put("content", responsesContent(message.get("content")));
                if (message.get("tool_calls") != null) {
                    // Responses expects function_call items instead of assistant tool_calls.
                    @SuppressWarnings("unchecked") List<Map<String, Object>> calls =
                        (List<Map<String, Object>>) message.get("tool_calls");
                    for (Map<String, Object> call : calls) {
                        @SuppressWarnings("unchecked") Map<String, Object> function =
                            (Map<String, Object>) call.get("function");
                        Map<String, Object> functionCall = new LinkedHashMap<>();
                        functionCall.put("type", "function_call");
                        functionCall.put("call_id", call.get("id"));
                        functionCall.put("name", function.get("name"));
                        functionCall.put("arguments", function.get("arguments"));
                        result.add(functionCall);
                    }
                } else {
                    result.add(item);
                }
            }
        }
        return result;
    }

    private Object responsesContent(Object content) {
        if (!(content instanceof List)) return content;
        List<?> source = (List<?>) content;
        List<Map<String, Object>> result = new ArrayList<>(source.size());
        for (Object value : source) {
            if (!(value instanceof Map)) {
                Map<String, Object> text = new LinkedHashMap<>();
                text.put("type", "input_text");
                text.put("text", value);
                result.add(text);
                continue;
            }
            @SuppressWarnings("unchecked") Map<String, Object> part = (Map<String, Object>) value;
            String type = String.valueOf(part.get("type"));
            Map<String, Object> converted = new LinkedHashMap<>();
            if ("text".equals(type)) {
                converted.put("type", "input_text");
                converted.put("text", part.get("text"));
            } else if ("image_url".equals(type)) {
                converted.put("type", "input_image");
                Object image = part.get("image_url");
                if (image instanceof Map) image = ((Map<?, ?>) image).get("url");
                converted.put("image_url", image);
            } else {
                converted.putAll(part);
            }
            result.add(converted);
        }
        return result;
    }
}
