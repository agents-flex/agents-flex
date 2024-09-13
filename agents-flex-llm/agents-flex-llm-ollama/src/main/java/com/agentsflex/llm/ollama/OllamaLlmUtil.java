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

import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.MessageStatus;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.FunctionMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.parser.impl.DefaultFunctionMessageParser;
import com.agentsflex.core.prompt.DefaultPromptFormat;
import com.agentsflex.core.prompt.ImagePrompt;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.PromptFormat;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson.JSON;

import java.util.Base64;
import java.util.Map;

public class OllamaLlmUtil {

    private static final HttpClient imageHttpClient = new HttpClient();

    private static final PromptFormat promptFormat = new DefaultPromptFormat() {
        @Override
        protected void buildMessageContent(Message message, Map<String, Object> map) {
            if (message instanceof ImagePrompt.TextAndImageMessage) {
                ImagePrompt prompt = ((ImagePrompt.TextAndImageMessage) message).getPrompt();
                map.put("content", prompt.getContent());
                map.put("images", new String[]{imageUrlToBase64(prompt.getImageUrl())});
            } else {
                super.buildMessageContent(message, map);
            }
        }
    };


    private static String imageUrlToBase64(String imageUrl) {
        byte[] bytes = imageHttpClient.getBytes(imageUrl);
        return Base64.getEncoder().encodeToString(bytes);
    }


    public static AiMessageParser getAiMessageParser() {
        DefaultAiMessageParser aiMessageParser = new DefaultAiMessageParser();
        aiMessageParser.setContentPath("$.message.content");
        aiMessageParser.setStatusPath("$.done");
        aiMessageParser.setStatusParser(content -> {
            if (content != null && (boolean) content) {
                return MessageStatus.END;
            }
            return MessageStatus.MIDDLE;
        });

        aiMessageParser.setTotalTokensPath("$.eval_count");
        aiMessageParser.setCompletionTokensPath("$.prompt_eval_count");
        return aiMessageParser;
    }

    public static FunctionMessageParser getFunctionMessageParser() {
        DefaultFunctionMessageParser functionMessageParser = new DefaultFunctionMessageParser();
        functionMessageParser.setFunctionNamePath("$.message.tool_calls[0].function.name");
        functionMessageParser.setFunctionArgsPath("$.message.tool_calls[0].function.arguments");
        functionMessageParser.setFunctionArgsParser(JSON::parseObject);
        return functionMessageParser;
    }


    public static String promptToPayload(Prompt<?> prompt, OllamaLlmConfig config, ChatOptions options, boolean stream) {
        return Maps.of("model", config.getModel())
            .put("messages", promptFormat.toMessagesJsonObject(prompt))
            .putIf(!stream, "stream", stream)
            .putIfNotEmpty("tools", promptFormat.toFunctionsJsonObject(prompt))
            .putIfNotEmpty("options.seed", options.getSeed())
            .putIfNotEmpty("options.top_k", options.getTopK())
            .putIfNotEmpty("options.top_p", options.getTopP())
            .putIfNotEmpty("options.temperature", options.getTemperature())
            .putIfNotEmpty("options.stop", options.getStop())
            .toJSON();
    }

}
