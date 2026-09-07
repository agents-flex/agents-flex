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

import com.agentsflex.core.message.*;
import com.agentsflex.core.model.chat.BaseChatConfig;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.client.OpenAIChatMessageSerializer;
import com.agentsflex.core.model.client.OpenAIChatRequestSpecBuilder;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson2.JSON;
import java.util.*;

/** Maps framework messages to Responses input items without server-side conversation state. */
public class ResponsesRequestSpecBuilder extends OpenAIChatRequestSpecBuilder {
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
        Map<String, Object> format = options.getResponseFormat();
        if (format != null && !format.isEmpty()) {
            Map<String, Object> target = new LinkedHashMap<>(format);
            if ("json_schema".equals(format.get("type"))) {
                Object schema = target.remove("json_schema");
                if (!(schema instanceof Map)) throw new IllegalArgumentException("json_schema must be an object");
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
                function.put("strict", false);
                flattened.add(function);
            }
            body.set("tools", flattened);
            body.setIfNotNull("tool_choice", prompt.getToolChoice());
        }
        if (options.getExtraBody() != null) body.putAll(options.getExtraBody());
        // This adapter replays local history, including opaque reasoning items.
        if (Boolean.TRUE.equals(body.get("background")) || body.containsKey("previous_response_id")
            || body.containsKey("conversation")) {
            throw new IllegalArgumentException("Responses adapter uses local history; background and server-side conversations are unsupported");
        }
        return body.toJSON();
    }

    private List<Object> input(List<Message> messages) {
        List<Object> items = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof ToolMessage) {
                ToolMessage tool = (ToolMessage) message;
                items.add(Maps.of("type", "function_call_output").set("call_id", tool.getToolCallId())
                    .set("output", text(tool.getTextContent())));
            } else if (message instanceof AiMessage) {
                AiMessage ai = (AiMessage) message;
                Object reasoning = ai.getMetadata(ResponsesResponseParser.REASONING_ITEMS);
                if (reasoning instanceof String) items.addAll(JSON.parseArray((String) reasoning));
                String content = ai.getTextContent();
                if (content == null) content = ai.getFullContent();
                if (content != null && !content.isEmpty()) items.add(Maps.of("role", "assistant").set("content", content));
                if (ai.getToolCalls() != null) {
                    for (ToolCall call : ai.getToolCalls()) {
                        items.add(Maps.of("type", "function_call").set("call_id", call.getId())
                            .set("name", call.getName()).set("arguments", call.getArguments()));
                    }
                }
            } else if (message instanceof UserMessage || message instanceof SystemMessage) {
                if (message instanceof UserMessage) {
                    UserMessage user = (UserMessage) message;
                    if (hasItems(user.getImageUrls()) || hasItems(user.getFileUrls())
                        || hasItems(user.getAudioUrls()) || hasItems(user.getVideoUrls())) {
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

    private static boolean hasItems(List<?> values) { return values != null && !values.isEmpty(); }
    private static String text(String value) { return value == null ? "" : value; }
}
