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

import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.message.*;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.message.OpenAIMessageFormat;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.message.MessageFormat;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.MessageUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONPath;

import java.util.*;

public class OllamaLlmUtil {


    private static final MessageFormat MESSAGE_FORMAT = new OpenAIMessageFormat() {
//        @Override
//        protected void buildMessageContent(Message message, Map<String, Object> map) {
//            if (message instanceof UserImageMessage) {
//                ImagePrompt prompt = ((UserImageMessage) message).getPrompt();
//                map.put("content", prompt.getContent());
//                map.put("images", prompt.buildAllToBase64s());
//            } else {
//                super.buildMessageContent(message, map);
//            }
//        }
//
//        @Override
//        protected Object buildToolCallsArguments(Map<String, Object> arguments) {
//            return arguments;
//        }
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


    public static String promptToPayload(Prompt prompt, OllamaChatConfig config, ChatOptions options, boolean stream) {
        List<Message> messages = prompt.toMessages();
        UserMessage message = MessageUtil.findLastHumanMessage(messages);
        return Maps.of("model", Optional.ofNullable(options.getModel()).orElse(config.getModel()))
            .set("messages", MESSAGE_FORMAT.toMessagesJsonObject(messages))
            .set("think", Optional.ofNullable(options.getEnableThinking()).orElse(config.getEnableThinking()))
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
