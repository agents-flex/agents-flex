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
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.BaseChatConfig;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.client.OpenAIChatMessageSerializer;
import com.agentsflex.core.model.client.OpenAIChatRequestSpecBuilder;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses 请求构建器。
 * <p>
 * 将框架消息转换为 {@code input} 项，并映射工具定义、输出 Token 限制和结构化输出格式。
 * URL、请求头及重试配置复用基础实现；本类不保存请求状态，可由模型并发复用。
 */
public class ResponsesRequestSpecBuilder extends OpenAIChatRequestSpecBuilder {

    /**
     * 基于拦截器处理后的参数生成 Responses 请求体。
     *
     * @param prompt    包含历史消息和工具定义的提示词
     * @param options   本次请求的选项
     * @param config    模型配置
     * @param streaming 是否使用流式输出
     * @return 序列化后的 JSON 请求体
     * @throws IllegalArgumentException 输入包含当前适配器不支持的能力时抛出
     */
    @Override
    public String buildRequestBody(Prompt prompt, ChatOptions options, BaseChatConfig config, boolean streaming) {
        Maps body = Maps.of("model", options.getModelOrDefault(config.getModel()))
            .set("input", input(prompt.getMessages()))
            .set("store", false)
            .set("include", Collections.singletonList("reasoning.encrypted_content"))
            .setIf(streaming, "stream", true)
            .setIfNotNull("temperature", options.getTemperature())
            .setIfNotNull("top_p", options.getTopP())
            .setIfNotNull("max_output_tokens", options.getMaxTokens());
        if (options.getStop() != null && !options.getStop().isEmpty()) {
            throw new IllegalArgumentException("Responses does not support stop sequences");
        }
        // Responses 使用 text.format；JSON Schema 的 name、schema、strict 需要移到同一层。
        Map<String, Object> format = options.getResponseFormat();
        if (format != null && !format.isEmpty()) {
            Map<String, Object> target = new LinkedHashMap<>(format);
            if ("json_schema".equals(format.get("type"))) {
                Object schema = target.remove("json_schema");
                if (!(schema instanceof Map)) {
                    throw new IllegalArgumentException("json_schema must be an object");
                }
                target.putAll((Map<String, Object>) schema);
                target.put("type", "json_schema");
            }
            body.set("text", Maps.of("format", target));
        }
        List<Map<String, Object>> tools = new OpenAIChatMessageSerializer().serializeTools(prompt.getTools(), config);
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> flattened = new ArrayList<>();
            for (Map<String, Object> tool : tools) {
                if (!"function".equals(tool.get("type"))) {
                    throw new IllegalArgumentException("Responses adapter supports local function tools only");
                }
                Map<String, Object> function = new LinkedHashMap<>((Map<String, Object>) tool.get("function"));
                function.put("type", "function");
                // 保留框架工具中可选参数的语义，避免服务端隐式启用严格模式。
                function.put("strict", false);
                flattened.add(function);
            }
            body.set("tools", flattened);
            body.setIfNotNull("tool_choice", prompt.getToolChoice());
        }
        if (options.getExtraBody() != null) {
            body.putAll(options.getExtraBody());
        }
        // 最后检查扩展参数，避免服务端会话与本地历史回传同时生效。
        if (Boolean.TRUE.equals(body.get("background")) || body.containsKey("previous_response_id")
            || body.containsKey("conversation")) {
            throw new IllegalArgumentException(
                "Responses adapter uses local history; background and server-side conversations are unsupported");
        }
        return body.toJSON();
    }

    /**
     * 按历史顺序转换消息，并保留工具调用的 call_id 和加密推理项。
     *
     * @param messages 框架历史消息
     * @return Responses input 项
     */
    private List<Object> input(List<Message> messages) {
        List<Object> items = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof ToolMessage) {
                ToolMessage tool = (ToolMessage) message;
                items.add(Maps.of("type", "function_call_output")
                    .set("call_id", tool.getToolCallId())
                    .set("output", text(tool.getTextContent())));
            } else if (message instanceof AiMessage) {
                AiMessage ai = (AiMessage) message;
                // 加密推理项无法从正文或摘要还原，必须从消息元数据原样回传。
                Object reasoning = ai.getMetadata(ResponsesResponseParser.REASONING_ITEMS);
                if (reasoning instanceof String) {
                    items.addAll(JSON.parseArray((String) reasoning));
                }
                String content = ai.getTextContent();
                if (content == null) {
                    content = ai.getFullContent();
                }
                if (content != null && !content.isEmpty()) {
                    items.add(Maps.of("role", "assistant").set("content", content));
                }
                if (ai.getToolCalls() != null) {
                    for (ToolCall call : ai.getToolCalls()) {
                        items.add(Maps.of("type", "function_call")
                            .set("call_id", call.getId())
                            .set("name", call.getName())
                            .set("arguments", call.getArguments()));
                    }
                }
            } else if (message instanceof UserMessage || message instanceof SystemMessage) {
                if (message instanceof UserMessage) {
                    UserMessage user = (UserMessage) message;
                    if (hasItems(user.getImageUrls()) || hasItems(user.getFileUrls()) || hasItems(user.getAudioUrls())
                        || hasItems(user.getVideoUrls())) {
                        throw new IllegalArgumentException("Responses adapter currently supports text input only");
                    }
                }
                items.add(Maps.of("role", message instanceof UserMessage ? "user" : "system")
                    .set("content", text(message.getTextContent())));
            } else {
                throw new IllegalArgumentException("Unsupported Responses message: " + message.getClass().getName());
            }
        }
        return items;
    }

    private static boolean hasItems(List<?> values) {
        return values != null && !values.isEmpty();
    }
    private static String text(String value) {
        return value == null ? "" : value;
    }
}
