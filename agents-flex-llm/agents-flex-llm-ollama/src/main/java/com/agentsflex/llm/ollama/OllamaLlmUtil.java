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
import com.agentsflex.core.message.FunctionCall;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.MessageStatus;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.prompt.DefaultPromptFormat;
import com.agentsflex.core.prompt.ImagePrompt;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.PromptFormat;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class OllamaLlmUtil {


    private static final PromptFormat promptFormat = new DefaultPromptFormat() {
        @Override
        protected void buildMessageContent(Message message, Map<String, Object> map) {
            if (message instanceof ImagePrompt.TextAndImageMessage) {
                ImagePrompt prompt = ((ImagePrompt.TextAndImageMessage) message).getPrompt();
                map.put("content", prompt.getContent());
//                map.put("images", new String[]{ImageUtil.imageUrlToBase64(prompt.getImageUrl())});
                map.put("images", new String[]{prompt.toImageBase64()});
            } else {
                super.buildMessageContent(message, map);
            }
        }

        @Override
        protected Object buildToolCallsArguments(Map<String, Object> arguments) {
            return arguments;
        }
    };


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
                    functionCall.setId(jsonObject.getString("id"));
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
        return Maps.of("model", Optional.ofNullable(options.getModel()).orElse(config.getModel()))
            .set("messages", promptFormat.toMessagesJsonObject(messages))
            .set("think", Optional.ofNullable(options.getEnableThinking()).orElse(config.getEnableThinking()))
            .setIf(!stream, "stream", stream)
            .setIfNotEmpty("tools", promptFormat.toFunctionsJsonObject(CollectionUtil.lastItem(messages)))
            .setIfNotEmpty("options.seed", options.getSeed())
            .setIfNotEmpty("options.top_k", options.getTopK())
            .setIfNotEmpty("options.top_p", options.getTopP())
            .setIfNotEmpty("options.temperature", options.getTemperature())
            .setIfNotEmpty("options.stop", options.getStop())
            .toJSON();
    }

}
