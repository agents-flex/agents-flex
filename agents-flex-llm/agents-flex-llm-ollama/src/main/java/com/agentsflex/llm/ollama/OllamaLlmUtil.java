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
import com.agentsflex.core.message.FunctionCall;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.MessageStatus;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.prompt.DefaultPromptFormat;
import com.agentsflex.core.prompt.ImagePrompt;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.PromptFormat;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.*;

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
        aiMessageParser.setTotalTokensPath("$.eval_count");
        aiMessageParser.setCompletionTokensPath("$.prompt_eval_count");

        aiMessageParser.setStatusParser(content -> {
            Boolean done = (Boolean) JSONPath.eval(content, "$.done");
            if (done != null && done) {
                return MessageStatus.END;
            }
            return MessageStatus.MIDDLE;
        });

        aiMessageParser.setCallsParser(content -> {
            JSONArray toolCalls = (JSONArray) JSONPath.eval(content, "$.message.tool_calls");
            if (toolCalls == null || toolCalls.isEmpty()) {
                return Collections.emptyList();
            }
            List<FunctionCall> functionCalls = new ArrayList<>();
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject jsonObject = toolCalls.getJSONObject(i);
                JSONObject functionObject = jsonObject.getJSONObject("function");
                if (functionObject != null) {
                    FunctionCall functionCall = new FunctionCall();
                    functionCall.setName(functionObject.getString("name"));
                    Object arguments = functionObject.get("arguments");
                    if (arguments instanceof Map) {
                        //noinspection unchecked
                        functionCall.setArgs((Map<String, Object>) arguments);
                    } else if (arguments instanceof String) {
                        //noinspection unchecked
                        functionCall.setArgs(JSON.parseObject(arguments.toString(), Map.class));
                    }
                    functionCalls.add(functionCall);
                }
            }
            return functionCalls;
        });
        return aiMessageParser;
    }


    public static String promptToPayload(Prompt prompt, OllamaLlmConfig config, ChatOptions options, boolean stream) {
        List<Message> messages = prompt.toMessages();
        return Maps.of("model", config.getModel())
            .put("messages", promptFormat.toMessagesJsonObject(messages))
            .putIf(!stream, "stream", stream)
            .putIfNotEmpty("tools", promptFormat.toFunctionsJsonObject(messages.get(messages.size() - 1)))
            .putIfNotEmpty("options.seed", options.getSeed())
            .putIfNotEmpty("options.top_k", options.getTopK())
            .putIfNotEmpty("options.top_p", options.getTopP())
            .putIfNotEmpty("options.temperature", options.getTemperature())
            .putIfNotEmpty("options.stop", options.getStop())
            .toJSON();
    }

}
