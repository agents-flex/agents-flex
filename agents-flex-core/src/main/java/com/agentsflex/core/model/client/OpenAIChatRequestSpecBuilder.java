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

    private final ChatMessageSerializer chatMessageSerializer;

    public OpenAIChatRequestSpecBuilder() {
        this(new OpenAIChatMessageSerializer());
    }

    public OpenAIChatRequestSpecBuilder(ChatMessageSerializer chatMessageSerializer) {
        this.chatMessageSerializer = chatMessageSerializer;
    }

    @Override
    public ChatRequestSpec buildRequest(Prompt prompt, ChatConfig config, ChatOptions options) {

        // 1. 构建 payload JSON 字符串（复用你原有的逻辑）
        String body = buildRequestBody(prompt, config, options);

        // 2. 构建 headers
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        // 3 . 构建 URL
        String url = config.getFullUrl();

        return new ChatRequestSpec(url, headers, body);
    }


    protected String buildRequestBody(Prompt prompt, ChatConfig config, ChatOptions options) {
        List<Message> messages = prompt.getMessages();
        UserMessage userMessage = MessageUtil.findLastUserMessage(messages);

        Maps map = Maps.of("model", options.getModelOrDefault(config.getModel()))
            .setIf(options.isStreaming(), "stream", true)
            .setIfNotNull("top_p", options.getTopP())
            .setIfNotNull("top_k", options.getTopK())
            .setIfNotNull("temperature", options.getTemperature())
            .setIfNotNull("max_tokens", options.getMaxTokens())
            .setIfNotEmpty("stop", options.getStop());

        return map
            .set("messages", chatMessageSerializer.serializeMessages(messages, config))
            .setIfNotEmpty("tools", chatMessageSerializer.serializeTools(userMessage, config))
            .setIfContainsKey("tools", "tool_choice", userMessage != null ? userMessage.getToolChoice() : null)
            .toJSON();

    }
}
