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
package com.agentsflex.core.model.client;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatConfig;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.MessageUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenAIChatRequestSpecBuilder implements ChatRequestSpecBuilder {

    protected ChatMessageSerializer chatMessageSerializer;

    public OpenAIChatRequestSpecBuilder() {
        this(new OpenAIChatMessageSerializer());
    }

    public OpenAIChatRequestSpecBuilder(ChatMessageSerializer chatMessageSerializer) {
        this.chatMessageSerializer = chatMessageSerializer;
    }

    @Override
    public ChatRequestSpec buildRequest(Prompt prompt, ChatOptions options, ChatConfig config) {

        String url = buildRequestUrl(prompt, options, config);
        Map<String, String> headers = buildRequestHeaders(prompt, options, config);
        String body = buildRequestBody(prompt, options, config);

        boolean retryEnabled = options.getRetryEnabledOrDefault(config.isRetryEnabled());
        int retryCountOrDefault = options.getRetryCountOrDefault(config.getRetryCount());
        int retryInitialDelayMsOrDefault = options.getRetryInitialDelayMsOrDefault(config.getRetryInitialDelayMs());

        return new ChatRequestSpec(url, headers, body, retryEnabled ? retryCountOrDefault : 0, retryEnabled ? retryInitialDelayMsOrDefault : 0);
    }

    protected String buildRequestUrl(Prompt prompt, ChatOptions options, ChatConfig config) {
        return config.getFullUrl();
    }

    protected Map<String, String> buildRequestHeaders(Prompt prompt, ChatOptions options, ChatConfig config) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        return headers;
    }


    protected String buildRequestBody(Prompt prompt, ChatOptions options, ChatConfig config) {
        List<Message> messages = prompt.getMessages();
        UserMessage userMessage = MessageUtil.findLastUserMessage(messages);

        Maps baseBodyJsonMap = buildBaseParamsOfRequestBody(prompt, options, config);

        Maps bodyJsonMap = baseBodyJsonMap
            .set("messages", chatMessageSerializer.serializeMessages(messages, config))
            .setIfNotEmpty("tools", chatMessageSerializer.serializeTools(userMessage, config))
            .setIfContainsKey("tools", "tool_choice", userMessage != null ? userMessage.getToolChoice() : null);

        if (options.isStreaming() && options.getIncludeUsageOrDefault(true)) {
            bodyJsonMap.set("stream_options", Maps.of("include_usage", true));
        }

        buildThinkingBody(options, config, bodyJsonMap);

        if (options.getExtraBody() != null) {
            bodyJsonMap.putAll(options.getExtraBody());
        }

        return bodyJsonMap.toJSON();
    }

    protected void buildThinkingBody(ChatOptions options, ChatConfig config, Maps bodyJsonMap) {
        if (!config.isSupportThinking()) {
            return;
        }
        Boolean thinkingEnabled = options.getThinkingEnabled();
        if (thinkingEnabled == null) {
            return;
        }

        String thinkingProtocol = config.getThinkingProtocol();
        if (thinkingProtocol == null || "none".equals(thinkingProtocol)) {
            return;
        }

        switch (thinkingProtocol) {
            case "qwen":
                bodyJsonMap.set("enable_thinking", thinkingEnabled);
                break;
            case "deepseek":
                bodyJsonMap.set("thinking", Maps.of("type", thinkingEnabled ? "enabled" : "disabled"));
                break;
            case "ollama":
                bodyJsonMap.set("thinking", thinkingEnabled);
            default:
                // do nothing
        }
    }


    protected Maps buildBaseParamsOfRequestBody(Prompt prompt, ChatOptions options, ChatConfig config) {
        return Maps.of("model", options.getModelOrDefault(config.getModel()))
            .setIf(options.isStreaming(), "stream", true)
            .setIfNotNull("top_p", options.getTopP())
//            .setIfNotNull("top_k", options.getTopK()) // openAI 不支持 top_k 标识
            .setIfNotNull("temperature", options.getTemperature())
            .setIfNotNull("max_tokens", options.getMaxTokens())
            .setIfNotEmpty("stop", options.getStop())
            .setIfNotEmpty("response_format", options.getResponseFormat());

    }

    public ChatMessageSerializer getChatMessageSerializer() {
        return chatMessageSerializer;
    }

    public void setChatMessageSerializer(ChatMessageSerializer chatMessageSerializer) {
        this.chatMessageSerializer = chatMessageSerializer;
    }
}
