/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.model.chat.openai.responses;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Responses 最终响应解析器。
 * <p>
 * 支持 completed 和 incomplete 响应，将文本、拒答、工具调用及 Token 用量映射到框架消息。
 * 加密推理项保存在消息元数据中，供后续本地历史回传使用。
 * 解析过程不持有共享可变状态，同步客户端和流式终止事件可复用该实现。
 */
public class ResponsesResponseParser {

    /**
     * 消息元数据中的推理项键名；值为 JSON 字符串，便于消息复制和历史存储。
     */
    public static final String REASONING_ITEMS = "openai.responses.reasoning_items";

    /**
     * 解析完整响应对象；流式调用应传入终止事件中的 response，而非整个事件。
     *
     * @param raw     完整响应的原始 JSON
     * @param context 本次调用上下文
     * @return 成功消息或携带服务端错误信息的响应
     * @throws com.alibaba.fastjson2.JSONException 输入不是合法 JSON 时抛出，由客户端处理
     */
    public AiMessageResponse parse(String raw, ChatContext context) {
        JSONObject root = JSON.parseObject(raw);
        if (root == null) {
            return AiMessageResponse.error(context, raw, "Empty Responses object");
        }
        JSONObject error = root.getJSONObject("error");
        String status = root.getString("status");
        if (error != null || !("completed".equals(status) || "incomplete".equals(status))) {
            AiMessageResponse result = AiMessageResponse.error(
                context, raw, error == null ? "Unexpected Responses status: " + status : error.getString("message"));
            if (error != null) {
                result.setErrorCode(error.getString("code"));
                result.setErrorType(error.getString("type"));
            }
            return result;
        }
        AiMessage ai = new AiMessage();
        ai.setId(root.getString("id"));
        ai.setObject(root.getString("object"));
        ai.setCreated(root.getLong("created_at"));
        ai.setModel(root.getString("model"));
        ai.setRole("assistant");
        ai.setServiceTier(root.getString("service_tier"));
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        StringBuilder refusal = new StringBuilder();
        List<Object> reasoningItems = new ArrayList<>();
        List<Object> annotations = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        JSONArray output = root.getJSONArray("output");
        if (output == null) {
            return AiMessageResponse.error(context, raw, "Missing Responses output");
        }
        for (int i = 0; i < output.size(); i++) {
            JSONObject item = output.getJSONObject(i);
            switch (item.getString("type")) {
                case "message":
                    JSONArray content = item.getJSONArray("content");
                    if (content == null) {
                        break;
                    }
                    for (int j = 0; j < content.size(); j++) {
                        JSONObject part = content.getJSONObject(j);
                        if ("output_text".equals(part.getString("type"))) {
                            append(text, part.getString("text"));
                            if (part.getJSONArray("annotations") != null) {
                                annotations.addAll(part.getJSONArray("annotations"));
                            }
                        } else if ("refusal".equals(part.getString("type"))) {
                            append(refusal, part.getString("refusal"));
                        }
                    }
                    break;
                case "function_call":
                    ToolCall call = new ToolCall();
                    // 工具结果关联的是 call_id，而不是输出项自身的 id。
                    call.setId(item.getString("call_id"));
                    call.setName(item.getString("name"));
                    call.setArguments(item.getString("arguments"));
                    calls.add(call);
                    break;
                case "reasoning":
                    reasoningItems.add(item);
                    JSONArray summary = item.getJSONArray("summary");
                    if (summary != null) {
                        for (int j = 0; j < summary.size(); j++) {
                            append(reasoning, summary.getJSONObject(j).getString("text"));
                        }
                    }
                    break;
                default:
                    return AiMessageResponse.error(
                        context, raw, "Unsupported Responses output item: " + item.getString("type"));
            }
        }
        ai.setContent(text.toString());
        ai.setFullContent(text.toString());
        if (reasoning.length() > 0) {
            ai.setReasoningContent(reasoning.toString());
        }
        if (refusal.length() > 0) {
            ai.setRefusal(refusal.toString());
        }
        if (!annotations.isEmpty()) {
            ai.setAnnotations(annotations);
        }
        if (!reasoningItems.isEmpty()) {
            ai.putMetadata(REASONING_ITEMS, JSON.toJSONString(reasoningItems));
        }
        // 未完成响应的函数参数可能被截断，不能交给 Agent 执行。
        if ("completed".equals(status)) {
            ai.setToolCalls(calls);
        }
        ai.setFinishReason(calls.isEmpty() ? "stop" : "tool_calls");
        if ("incomplete".equals(status)) {
            JSONObject details = root.getJSONObject("incomplete_details");
            String reason = details == null ? "incomplete" : details.getString("reason");
            ai.setFinishReason("max_output_tokens".equals(reason) ? "length" : reason);
        }
        ai.putMetadata("openai.responses.status", status);
        JSONObject usage = root.getJSONObject("usage");
        if (usage != null) {
            ai.setPromptTokens(usage.getInteger("input_tokens"));
            ai.setCompletionTokens(usage.getInteger("output_tokens"));
            ai.setTotalTokens(usage.getInteger("total_tokens"));
            ai.setPromptTokensDetails(usage.getJSONObject("input_tokens_details"));
            ai.setCompletionTokensDetails(usage.getJSONObject("output_tokens_details"));
        }
        ai.setFinished(true);
        return new AiMessageResponse(context, raw, ai);
    }

    private static void append(StringBuilder target, String value) {
        if (value != null) {
            target.append(value);
        }
    }
}
