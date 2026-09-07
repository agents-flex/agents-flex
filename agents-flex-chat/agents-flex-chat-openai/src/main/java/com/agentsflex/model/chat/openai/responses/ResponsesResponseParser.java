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
import com.alibaba.fastjson2.*;
import java.util.*;

/** Parses terminal Responses objects and preserves encrypted reasoning for local history replay. */
public class ResponsesResponseParser {
    public static final String REASONING_ITEMS = "openai.responses.reasoning_items";

    public AiMessageResponse parse(String raw, ChatContext context) {
        JSONObject root = JSON.parseObject(raw);
        if (root == null) return AiMessageResponse.error(context, raw, "Empty Responses object");
        JSONObject error = root.getJSONObject("error");
        String status = root.getString("status");
        if (error != null || !("completed".equals(status) || "incomplete".equals(status))) {
            AiMessageResponse result = AiMessageResponse.error(context, raw,
                error == null ? "Unexpected Responses status: " + status : error.getString("message"));
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
        if (output == null) return AiMessageResponse.error(context, raw, "Missing Responses output");
        for (int i = 0; i < output.size(); i++) {
            JSONObject item = output.getJSONObject(i);
            switch (item.getString("type")) {
                case "message":
                    JSONArray content = item.getJSONArray("content");
                    if (content == null) break;
                    for (int j = 0; j < content.size(); j++) {
                        JSONObject part = content.getJSONObject(j);
                        if ("output_text".equals(part.getString("type"))) {
                            append(text, part.getString("text"));
                            if (part.getJSONArray("annotations") != null) annotations.addAll(part.getJSONArray("annotations"));
                        } else if ("refusal".equals(part.getString("type"))) append(refusal, part.getString("refusal"));
                    }
                    break;
                case "function_call":
                    ToolCall call = new ToolCall();
                    call.setId(item.getString("call_id"));
                    call.setName(item.getString("name"));
                    call.setArguments(item.getString("arguments"));
                    calls.add(call);
                    break;
                case "reasoning":
                    reasoningItems.add(item);
                    JSONArray summary = item.getJSONArray("summary");
                    if (summary != null) for (int j = 0; j < summary.size(); j++) append(reasoning, summary.getJSONObject(j).getString("text"));
                    break;
                default:
                    return AiMessageResponse.error(context, raw, "Unsupported Responses output item: " + item.getString("type"));
            }
        }
        ai.setContent(text.toString());
        ai.setFullContent(text.toString());
        if (reasoning.length() > 0) ai.setReasoningContent(reasoning.toString());
        if (refusal.length() > 0) ai.setRefusal(refusal.toString());
        if (!annotations.isEmpty()) ai.setAnnotations(annotations);
        if (!reasoningItems.isEmpty()) ai.putMetadata(REASONING_ITEMS, JSON.toJSONString(reasoningItems));
        // Never expose partial function arguments as executable calls.
        if ("completed".equals(status)) ai.setToolCalls(calls);
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

    private static void append(StringBuilder target, String value) { if (value != null) target.append(value); }
}
