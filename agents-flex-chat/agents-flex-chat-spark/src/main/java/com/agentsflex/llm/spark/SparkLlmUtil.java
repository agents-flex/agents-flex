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
package com.agentsflex.llm.spark;

import com.agentsflex.core.message.*;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.functions.Function;
import com.agentsflex.core.model.chat.functions.Parameter;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.message.OpenAIMessageFormat;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.message.MessageFormat;
import com.agentsflex.core.util.HashUtil;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.MessageUtil;
import com.alibaba.fastjson2.*;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;

public class SparkLlmUtil {

    private static final MessageFormat MESSAGE_FORMAT = new OpenAIMessageFormat() {
        @Override
        protected void buildFunctionJsonArray(List<Map<String, Object>> functionsJsonArray, List<Function> functions) {
            for (Function function : functions) {
                Map<String, Object> propertiesMap = new HashMap<>();
                List<String> requiredProperties = new ArrayList<>();

                Parameter[] parameters = function.getParameters();
                if (parameters != null) {
                    for (Parameter parameter : parameters) {
                        if (parameter.isRequired()) {
                            requiredProperties.add(parameter.getName());
                        }
                        propertiesMap.put(parameter.getName(), Maps.of("type", parameter.getType()).set("description", parameter.getDescription()));
                    }
                }

                Maps builder = Maps.of("name", function.getName())
                    .set("description", function.getDescription())
                    .set("parameters", Maps.of("type", "object").set("properties", propertiesMap).set("required", requiredProperties));
                functionsJsonArray.add(builder);
            }
        }
    };


    public static AiMessageParser getAiMessageParser() {
        DefaultAiMessageParser aiMessageParser = new DefaultAiMessageParser() {
            @Override
            public AiMessage parse(JSONObject rootJson) {
                if (!rootJson.containsKey("payload")) {
                    throw new JSONException("json not contains payload: " + rootJson);
                }
                return super.parse(rootJson);
            }
        };
        aiMessageParser.setContentPath("$.payload.choices.text[0].content");
        aiMessageParser.setIndexPath("$.payload.choices.text[0].index");
        aiMessageParser.setCompletionTokensPath("$.payload.usage.text.completion_tokens");
        aiMessageParser.setPromptTokensPath("$.payload.usage.text.prompt_tokens");
        aiMessageParser.setTotalTokensPath("$.payload.usage.text.total_tokens");

        aiMessageParser.setStatusParser(content -> {
            Integer status = (Integer) JSONPath.eval(content, "$.payload.choices.status");
            if (status == null) {
                return MessageStatus.UNKNOW;
            }
            switch (status) {
                case 0:
                    return MessageStatus.START;
                case 1:
                    return MessageStatus.MIDDLE;
                case 2:
                    return MessageStatus.END;
            }
            return MessageStatus.UNKNOW;
        });

        aiMessageParser.setCallsParser(content -> {
            JSONArray toolCalls = (JSONArray) JSONPath.eval(content, "$.payload.choices.text");
            if (toolCalls == null || toolCalls.isEmpty()) {
                return Collections.emptyList();
            }
            List<FunctionCall> functionCalls = new ArrayList<>();
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject jsonObject = toolCalls.getJSONObject(i);
                JSONObject functionObject = jsonObject.getJSONObject("function_call");
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


    public static String promptToPayload(Prompt prompt, SparkChatConfig config, ChatOptions options) {
        // https://www.xfyun.cn/doc/spark/Web.html#_1-%E6%8E%A5%E5%8F%A3%E8%AF%B4%E6%98%8E
        List<Message> messages = prompt.getMessages();
        UserMessage message = MessageUtil.findLastUserMessage(messages);
        Maps root = Maps.of("header", Maps.of("app_id", config.getAppId()).set("uid", UUID.randomUUID().toString().replaceAll("-", "")));
        root.set("parameter", Maps.of("chat", Maps.of("domain", getDomain(config.getVersion()))
                .setIf(options.getTemperature() > 0, "temperature", options.getTemperature())
                .setIf(options.getMaxTokens() != null, "max_tokens", options.getMaxTokens())
                .setIfNotNull("top_k", options.getTopK())
            )
        );
        root.set("payload", Maps.of("message", Maps.of("text", MESSAGE_FORMAT.toMessagesJsonObject(messages)))
            .setIfNotEmpty("functions", Maps.ofNotNull("text", MESSAGE_FORMAT.toFunctionsJsonObject(message)))
        );
        root.setIfNotEmpty(options.getExtra());
        return JSON.toJSONString(root);
    }


    public static String createURL(SparkChatConfig config) {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss '+0000'", Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String date = sdf.format(new Date());

        String header = "host: spark-api.xf-yun.com\n";
        header += "date: " + date + "\n";
        header += "GET /" + config.getVersion() + "/chat HTTP/1.1";

        String base64 = HashUtil.hmacSHA256ToBase64(header, config.getApiSecret());
        String authorization_origin = "api_key=\"" + config.getApiKey()
            + "\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"" + base64 + "\"";

        String authorization = Base64.getEncoder().encodeToString(authorization_origin.getBytes());
        return "ws://spark-api.xf-yun.com/" + config.getVersion() + "/chat?authorization=" + authorization
            + "&date=" + urlEncode(date) + "&host=spark-api.xf-yun.com";
    }

    private static String urlEncode(String content) {
        try {
            return URLEncoder.encode(content, "utf-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }


    private static String getDomain(String version) {
        switch (version) {
            case "v4.0":
                return "4.0Ultra";
            case "v3.5":
                return "generalv3.5";
            case "v3.1":
                return "generalv3";
            case "v2.1":
                return "generalv2";
            case "v1.1":
                return "lite";
            default:
                return "general";
        }
    }

}
