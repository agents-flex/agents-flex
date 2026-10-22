package com.agentsflex.core.parser.impl;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.parser.AiMessageParser;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Converts Responses API objects and stream events to the common AiMessage model. */
public final class OpenAIResponsesParser implements AiMessageParser<JSONObject> {
    private final Map<String, String> callIdsByItemId = new ConcurrentHashMap<>();
    @Override
    public AiMessage parse(JSONObject root, ChatContext context) {
        AiMessage message = new AiMessage();
        String type = root.getString("type");
        JSONObject response = root.getJSONObject("response");
        if (response == null && ("response".equals(type) || root.containsKey("output"))) {
            response = root;
        }
        if (response != null) {
            copyUsage(response, message);
            message.setId(response.getString("id"));
            if ("response.completed".equals(type)) {
                message.setFinished(true);
                callIdsByItemId.clear();
                return message;
            }
            parseOutput(response.getJSONArray("output"), message);
            if (message.getContent() == null) {
                message.setContent(response.getString("output_text"));
            }
            return message;
        }

        if ("response.output_text.delta".equals(type)) {
            message.setContent(root.getString("delta"));
        } else if ("response.reasoning_text.delta".equals(type)
            || "response.reasoning_summary_text.delta".equals(type)) {
            message.setReasoningContent(root.getString("delta"));
        } else if ("response.output_item.added".equals(type)) {
            parseOutputItem(root.getJSONObject("item"), message);
        } else if ("response.function_call_arguments.delta".equals(type)) {
            String itemId = root.getString("item_id");
            ToolCall call = new ToolCall(resolveCallId(itemId), null, root.getString("delta"));
            message.setToolCalls(java.util.Collections.singletonList(call));
        } else if ("response.completed".equals(type)) {
            message.setFinished(true);
        }
        return message;
    }

    private void parseOutput(JSONArray output, AiMessage message) {
        if (output == null) return;
        for (Object value : output) {
            if (value instanceof JSONObject) parseOutputItem((JSONObject) value, message);
        }
    }

    private void parseOutputItem(JSONObject item, AiMessage message) {
        if (item == null) return;
        String type = item.getString("type");
        if ("function_call".equals(type)) {
            String itemId = item.getString("id");
            String callId = item.getString("call_id");
            if (itemId != null && callId != null) callIdsByItemId.put(itemId, callId);
            message.setToolCalls(java.util.Collections.singletonList(new ToolCall(
                callId != null ? callId : itemId,
                item.getString("name"), item.getString("arguments"))));
            return;
        }
        if ("reasoning".equals(type)) {
            String reasoning = readText(item.getJSONArray("content"), "reasoning_text");
            if (reasoning.isEmpty()) {
                reasoning = readText(item.getJSONArray("summary"), "summary_text");
            }
            if (!reasoning.isEmpty()) {
                String previous = message.getReasoningContent();
                message.setReasoningContent(previous == null ? reasoning : previous + reasoning);
            }
        } else if ("message".equals(type)) {
            String text = readText(item.getJSONArray("content"), "output_text");
            if (!text.isEmpty()) message.setContent(text);
        }
    }

    private static String readText(JSONArray content, String type) {
        StringBuilder text = new StringBuilder();
        if (content == null) return text.toString();
        for (Object value : content) {
            if (value instanceof JSONObject) {
                JSONObject part = (JSONObject) value;
                if (type.equals(part.getString("type"))) {
                    String partText = part.getString("text");
                    if (partText != null) text.append(partText);
                }
            }
        }
        return text.toString();
    }

    private String resolveCallId(String itemId) {
        String callId = callIdsByItemId.get(itemId);
        return callId == null ? itemId : callId;
    }

    private static void copyUsage(JSONObject response, AiMessage message) {
        JSONObject usage = response.getJSONObject("usage");
        if (usage == null) return;
        Integer input = usage.getInteger("input_tokens");
        Integer output = usage.getInteger("output_tokens");
        Integer total = usage.getInteger("total_tokens");
        if (input != null) message.setPromptTokens(input);
        if (output != null) message.setCompletionTokens(output);
        if (total != null) message.setTotalTokens(total);
        JSONObject inputDetails = usage.getJSONObject("input_tokens_details");
        JSONObject outputDetails = usage.getJSONObject("output_tokens_details");
        if (inputDetails != null) message.setPromptTokensDetails(inputDetails);
        if (outputDetails != null) message.setCompletionTokensDetails(outputDetails);
    }
}
