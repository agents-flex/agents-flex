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
import com.agentsflex.core.model.chat.BaseChatConfig;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.Maps;

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
    public ChatRequestSpec buildRequestSpec(Prompt prompt, ChatOptions options, BaseChatConfig config) {
        if (options == null) options = new ChatOptions();
        String url = buildRequestUrl(prompt, options, config);
        Map<String, String> headers = buildRequestHeaders(prompt, options, config);
        boolean retryEnabled = options.getRetryEnabledOrDefault(config.isRetryEnabled());
        int retryCountOrDefault = options.getRetryCountOrDefault(config.getRetryCount());
        int retryInitialDelayMsOrDefault = options.getRetryInitialDelayMsOrDefault(config.getRetryInitialDelayMs());

        return new ChatRequestSpec(url, headers, retryEnabled ? retryCountOrDefault : 0,
            retryEnabled ? retryInitialDelayMsOrDefault : 0);
    }

    protected String buildRequestUrl(Prompt prompt, ChatOptions options, BaseChatConfig config) {
        return config.getFullUrl();
    }

    protected Map<String, String> buildRequestHeaders(Prompt prompt, ChatOptions options, BaseChatConfig config) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        return headers;
    }


    @Override
    public String buildRequestBody(Prompt prompt, ChatOptions options, BaseChatConfig config) {
        return buildRequestBody(prompt, options, config, false);
    }

    @Override
    public String buildRequestBody(Prompt prompt, ChatOptions options, BaseChatConfig config,
                                   boolean streaming) {
        if (options == null) options = new ChatOptions();
        List<Message> messages = prompt.getMessages();

        Maps baseBodyJsonMap = buildBaseParamsOfRequestBody(prompt, options, config, streaming);

        Maps bodyJsonMap = baseBodyJsonMap
            .set("messages", chatMessageSerializer.serializeMessages(messages, config, options))
            .setIfNotEmpty("tools", chatMessageSerializer.serializeTools(prompt, config))
            .setIf(maps -> prompt.getToolChoice() != null
                && maps.containsKey("tools"), "tool_choice", prompt.getToolChoice());


        if (streaming && options.getIncludeUsageOrDefault(true)) {
            bodyJsonMap.set("stream_options", Maps.of("include_usage", true));
        }

        buildThinkingBody(options, config, bodyJsonMap);

        if (options.getExtraBody() != null) {
            for (Map.Entry<String, Object> entry : options.getExtraBody().entrySet()) {
                if (isThinkingParameter(entry.getKey()) && bodyJsonMap.containsKey(entry.getKey())
                    && !java.util.Objects.equals(bodyJsonMap.get(entry.getKey()), entry.getValue())) {
                    throw new IllegalArgumentException("Conflicting thinking parameter in extraBody: " + entry.getKey());
                }
                bodyJsonMap.put(entry.getKey(), entry.getValue());
            }
        }

        return bodyJsonMap.toJSON();
    }

    protected void buildThinkingBody(ChatOptions options, BaseChatConfig config, Maps bodyJsonMap) {
        Boolean thinkingEnabled = config.resolveThinkingEnabled(options);
        if (!config.isSupportThinking()) {
            if (thinkingEnabled != null) {
                throw new IllegalArgumentException("This model does not support overriding thinkingEnabled");
            }
            return;
        }
        config.getThinkingProtocolHandler().apply(thinkingEnabled, bodyJsonMap);
    }

    private boolean isThinkingParameter(String key) {
        return "thinking".equals(key) || "think".equals(key) || "enable_thinking".equals(key)
            || "reasoning_effort".equals(key);
    }


    protected Maps buildBaseParamsOfRequestBody(Prompt prompt, ChatOptions options,
                                                BaseChatConfig config, boolean streaming) {
        return Maps.of("model", options.getModelOrDefault(config.getModel()))
            .setIf(streaming, "stream", true)
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
