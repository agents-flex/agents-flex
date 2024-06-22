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

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.embedding.EmbeddingOptions;
import com.agentsflex.core.message.MessageStatus;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.FunctionMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.parser.impl.DefaultFunctionMessageParser;
import com.agentsflex.core.prompt.DefaultPromptFormat;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.PromptFormat;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson.JSON;

public class OpenAiLLmUtil {

    private static final PromptFormat promptFormat = new DefaultPromptFormat();

    public static AiMessageParser getAiMessageParser(boolean isStream) {
        DefaultAiMessageParser aiMessageParser = new DefaultAiMessageParser();
        if (isStream) {
            aiMessageParser.setContentPath("$.choices[0].delta.content");
        } else {
            aiMessageParser.setContentPath("$.choices[0].message.content");
        }

        aiMessageParser.setIndexPath("$.choices[0].index");
        aiMessageParser.setStatusPath("$.choices[0].finish_reason");
        aiMessageParser.setStatusParser(content -> parseMessageStatus((String) content));
        aiMessageParser.setTotalTokensPath("$.usage.total_tokens");
        aiMessageParser.setPromptTokensPath("$.usage.prompt_tokens");
        aiMessageParser.setCompletionTokensPath("$.usage.completion_tokens");

        return aiMessageParser;
    }


    public static FunctionMessageParser getFunctionMessageParser() {
        DefaultFunctionMessageParser functionMessageParser = new DefaultFunctionMessageParser();
        functionMessageParser.setFunctionNamePath("$.choices[0].message.tool_calls[0].function.name");
        functionMessageParser.setFunctionArgsPath("$.choices[0].message.tool_calls[0].function.arguments");
        functionMessageParser.setFunctionArgsParser(JSON::parseObject);
        return functionMessageParser;
    }


    public static MessageStatus parseMessageStatus(String status) {
        return "stop".equals(status) ? MessageStatus.END : MessageStatus.MIDDLE;
    }


    public static String promptToEmbeddingsPayload(Document text, EmbeddingOptions options, OpenAiLlmConfig config) {
        // https://platform.openai.com/docs/api-reference/making-requests
        return Maps.of("model", options.getModelOrDefault(config.getDefaultEmbeddingModal()))
            .put("encoding_format", "float")
            .put("input", text.getContent())
            .toJSON();
    }


    public static String promptToPayload(Prompt<?> prompt, OpenAiLlmConfig config, ChatOptions options, boolean withStream) {
        Maps.Builder builder = Maps.of("model", config.getModel())
            .put("messages", promptFormat.toMessagesJsonObject(prompt))
            .putIf(withStream, "stream", true)
            .putIfNotEmpty("tools", promptFormat.toFunctionsJsonObject(prompt))
            .putIfContainsKey("tools", "tool_choice", "auto")
            .putIfNotNull("top_p", options.getTopP())
            .putIfNotEmpty("stop", options.getStop())
            .putIf(map -> !map.containsKey("tools") && options.getTemperature() > 0, "temperature", options.getTemperature())
            .putIf(map -> !map.containsKey("tools") && options.getMaxTokens() != null, "max_tokens", options.getMaxTokens());

        return JSON.toJSONString(builder.build());
    }


}
