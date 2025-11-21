/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.llm.ollama;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.model.client.ChatMessageSerializer;
import com.agentsflex.core.model.client.OpenAIChatMessageSerializer;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatConfig;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatContextHolder;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.MessageUtil;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class OllamaLlmUtil {


    private static final ChatMessageSerializer MESSAGE_FORMAT = new OpenAIChatMessageSerializer() {
        @Override
        protected void buildMessageContent(Message message, Map<String, Object> map) {
            if (message instanceof UserMessage) {
                map.put("content", message.getTextContent());
                ChatContext chatContext = ChatContextHolder.currentContext();
                ChatConfig config = chatContext == null ? null : chatContext.getConfig();
                map.put("images", ((UserMessage) message).getImageUrlsForChat(config));
            } else {
                super.buildMessageContent(message, map);
            }
        }

    };



    public static String promptToPayload(Prompt prompt, OllamaChatConfig config, ChatOptions options, boolean stream) {
        List<Message> messages = prompt.getMessages();
        UserMessage message = MessageUtil.findLastUserMessage(messages);
        return Maps.of("model", Optional.ofNullable(options.getModel()).orElse(config.getModel()))
            .set("messages", MESSAGE_FORMAT.toMessagesJsonObject(messages))
            .set("think", config.isSupportThinking() ? Optional.ofNullable(options.getThinkingEnabled()).orElse(config.isThinkingEnabled()) : null)
            .setIf(!stream, "stream", stream)
            .setIfNotEmpty("tools", MESSAGE_FORMAT.toFunctionsJsonObject(message))
            .setIfNotEmpty("options.seed", options.getSeed())
            .setIfNotEmpty("options.top_k", options.getTopK())
            .setIfNotEmpty("options.top_p", options.getTopP())
            .setIfNotEmpty("options.temperature", options.getTemperature())
            .setIfNotEmpty("options.stop", options.getStop())
            .setIfNotEmpty(options.getExtra())
            .toJSON();
    }

}
