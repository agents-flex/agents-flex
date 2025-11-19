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
package com.agentsflex.llm.qwen;

import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.message.OpenAIMessageFormat;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.message.MessageFormat;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.MessageUtil;

import java.util.List;
import java.util.Optional;

public class QwenLlmUtil {

    private static final MessageFormat MESSAGE_FORMAT = new OpenAIMessageFormat();

    public static AiMessageParser getAiMessageParser(boolean isStream) {
        return DefaultAiMessageParser.getOpenAIMessageParser(isStream);
    }


    public static String promptToPayload(Prompt prompt, QwenChatConfig config, ChatOptions options, boolean withStream) {
        // https://help.aliyun.com/zh/dashscope/developer-reference/api-details?spm=a2c4g.11186623.0.0.1ff6fa70jCgGRc#b8ebf6b25eul6
        List<Message> messages = prompt.toMessages();
        UserMessage message = MessageUtil.findLastUserMessage(messages);
        Maps params = Maps.of("model", Optional.ofNullable(options.getModel()).orElse(config.getModel()))
            .set("messages", MESSAGE_FORMAT.toMessagesJsonObject(messages))
            .setIf(withStream, "stream", true)
            .setIf(withStream, "stream_options", Maps.of("include_usage", true))
            .setIfNotEmpty("tools", MESSAGE_FORMAT.toFunctionsJsonObject(message))
            .setIfContainsKey("tools", "tool_choice", MessageUtil.getToolChoice(message))
            .setIfNotNull("top_p", options.getTopP())
            .setIfNotEmpty("stop", options.getStop())
            .setIf(map -> !map.containsKey("tools") && options.getTemperature() > 0, "temperature", options.getTemperature())
            .setIf(map -> !map.containsKey("tools") && options.getMaxTokens() != null, "max_tokens", options.getMaxTokens());

        if (options instanceof QwenChatOptions) {
            QwenChatOptions op = (QwenChatOptions) options;
            params.setIf(CollectionUtil.hasItems(op.getModalities()), "modalities", op.getModalities());
            params.setIf(op.getPresencePenalty() != null, "presence_penalty", op.getPresencePenalty());
            params.setIf(op.getResponseFormat() != null, "response_format", op.getResponseFormat());
            params.setIf(op.getN() != null, "n", op.getN());
            params.setIf(op.getParallelToolCalls() != null, "parallel_tool_calls", op.getParallelToolCalls());
            params.setIf(op.getTranslationOptions() != null, "translation_options", op.getTranslationOptions());
            params.setIf(op.getEnableSearch() != null, "enable_search", op.getEnableSearch());
            params.setIf(op.getEnableSearch() != null && op.getEnableSearch() && op.getSearchOptions() != null, "search_options", op.getSearchOptions());
            params.setIf(op.getThinkingEnabled() != null, "enable_thinking", op.getThinkingEnabled());
            params.setIf(op.getThinkingEnabled() != null && op.getThinkingEnabled() && op.getThinkingBudget() != null, "thinking_budget", op.getThinkingBudget());
        }
        params.setIfNotEmpty(options.getExtra());
        return params.toJSON();
    }

}
