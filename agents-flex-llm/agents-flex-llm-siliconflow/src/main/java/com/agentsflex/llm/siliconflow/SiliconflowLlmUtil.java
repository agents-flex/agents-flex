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
package com.agentsflex.llm.siliconflow;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.LlmConfig;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.prompt.DefaultPromptFormat;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.PromptFormat;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.Maps;

import java.util.List;
import java.util.Optional;

public class SiliconflowLlmUtil {

    private static final PromptFormat promptFormat = new DefaultPromptFormat();

    public static AiMessageParser getAiMessageParser(boolean isStream) {
        return DefaultAiMessageParser.getChatGPTMessageParser(isStream);
    }


    public static String promptToPayload(Prompt prompt, LlmConfig config, ChatOptions options, boolean withStream) {
        List<Message> messages = prompt.toMessages();
        Message message = CollectionUtil.lastItem(messages);
        return Maps.of("model", Optional.ofNullable(options.getModel()).orElse(config.getModel()))
            .set("messages", promptFormat.toMessagesJsonObject(messages))
            .setIf(withStream, "stream", true)
            .setIfNotEmpty("tools", promptFormat.toFunctionsJsonObject(message))
            .setIfNotNull("top_p", options.getTopP())
            .setIfNotNull("top_k", options.getTopK())
            .setIfNotEmpty("stop", options.getStop())
            .setIf(map -> !map.containsKey("tools") && options.getTemperature() > 0, "temperature", options.getTemperature())
            .setIf(map -> !map.containsKey("tools") && options.getMaxTokens() != null, "max_tokens", options.getMaxTokens())
            .toJSON();
    }

    public static String documentToPayload(Document input, SiliconflowConfig config, EmbeddingOptions embeddingOptions) {
        return Maps.of("model", Optional.ofNullable(embeddingOptions.getModel()).orElse(config.getDefaultEmbeddingModel()))
            .set("input", input.getContent())
            .set("encoding_format", Optional.ofNullable(embeddingOptions.getEncodingFormat()).orElse("float"))
            .toJSON();
    }


}
