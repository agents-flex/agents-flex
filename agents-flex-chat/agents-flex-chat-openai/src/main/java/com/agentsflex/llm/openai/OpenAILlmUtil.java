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
package com.agentsflex.llm.openai;

import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.message.OpenAIMessageFormat;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.message.MessageFormat;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.MessageUtil;

import java.util.List;
import java.util.Optional;

public class OpenAILlmUtil {

    private static final MessageFormat MESSAGE_FORMAT = new OpenAIMessageFormat();

    public static AiMessageParser getAiMessageParser(boolean isStream) {
        return DefaultAiMessageParser.getOpenAIMessageParser(isStream);
    }


    public static String promptToPayload(Prompt prompt, OpenAIChatConfig config, ChatOptions options, boolean withStream) {
        List<Message> messages = prompt.getMessages();
        UserMessage message = MessageUtil.findLastUserMessage(messages);
        return Maps
            .of("model", Optional.ofNullable(options.getModel()).orElse(config.getModel()))
            .set("messages", MESSAGE_FORMAT.toMessagesJsonObject(messages))
            .setIf(withStream, "stream", true)
            .setIfNotEmpty("tools", MESSAGE_FORMAT.toFunctionsJsonObject(message))
            .setIfContainsKey("tools", "tool_choice", MessageUtil.getToolChoice(message))
            .setIfNotNull("top_p", options.getTopP())
            .setIfNotEmpty("stop", options.getStop())
            .setIf(map -> !map.containsKey("tools") && options.getTemperature() > 0, "temperature", options.getTemperature())
            .setIf(map -> !map.containsKey("tools") && options.getMaxTokens() != null, "max_tokens", options.getMaxTokens())
            .setIfNotEmpty(options.getExtra())
            .toJSON();
    }


}
