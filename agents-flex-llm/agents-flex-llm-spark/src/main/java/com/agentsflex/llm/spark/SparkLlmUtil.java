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

import com.agentsflex.core.document.Document;
import com.agentsflex.core.functions.Function;
import com.agentsflex.core.functions.Parameter;
import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.FunctionCall;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.MessageStatus;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.prompt.DefaultPromptFormat;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.PromptFormat;
import com.agentsflex.core.util.HashUtil;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson.*;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;

public class SparkLlmUtil {

    private static final PromptFormat promptFormat = new DefaultPromptFormat() {
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
                        propertiesMap.put(parameter.getName(), Maps.of("type", parameter.getType()).put("description", parameter.getDescription()));
                    }
                }

                Maps builder = Maps.of("name", function.getName())
                    .put("description", function.getDescription())
                    .put("parameters", Maps.of("type", "object").put("properties", propertiesMap).put("required", requiredProperties));
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
//        aiMessageParser.setStatusPath("$.payload.choices.status");
        aiMessageParser.setCompletionTokensPath("$.payload.usage.text.completion_tokens");
        aiMessageParser.setPromptTokensPath("$.payload.usage.text.prompt_tokens");
        aiMessageParser.setTotalTokensPath("$.payload.usage.text.total_tokens");
//        aiMessageParser.setStatusParser(content -> parseMessageStatus((Integer) content));


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

//            if (finishReason != null) {
//                return MessageStatus.END;
//            }
//            return MessageStatus.MIDDLE;
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


    public static String promptToPayload(Prompt prompt, SparkLlmConfig config, ChatOptions options) {
        // https://www.xfyun.cn/doc/spark/Web.html#_1-%E6%8E%A5%E5%8F%A3%E8%AF%B4%E6%98%8E
        List<Message> messages = prompt.toMessages();
        Maps root = Maps.of("header", Maps.of("app_id", config.getAppId()).put("uid", UUID.randomUUID()));
        root.put("parameter", Maps.of("chat", Maps.of("domain", getDomain(config.getVersion()))
                .putIf(options.getTemperature() > 0, "temperature", options.getTemperature())
                .putIf(options.getMaxTokens() != null, "max_tokens", options.getMaxTokens())
                .putIfNotNull("top_k", options.getTopK())
            )
        );
        root.put("payload", Maps.of("message", Maps.of("text", promptFormat.toMessagesJsonObject(messages)))
            .putIfNotEmpty("functions", Maps.ofNotNull("text", promptFormat.toFunctionsJsonObject(messages.get(messages.size() - 1))))
        );
        return JSON.toJSONString(root);
    }


    public static String createURL(SparkLlmConfig config) {
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

    public static String embedPayload(SparkLlmConfig config, Document document) {
        String text = Maps.of("messages", Collections.singletonList(Maps.of("content", document.getContent()).put("role", "user"))).toJSON();
        String textBase64 = Base64.getEncoder().encodeToString(text.getBytes());

        return Maps.of("header", Maps.of("app_id", config.getAppId()).put("uid", UUID.randomUUID()).put("status", 3))
            .put("parameter", Maps.of("emb", Maps.of("domain", "para").put("feature", Maps.of("encoding", "utf8").put("compress", "raw").put("format", "plain"))))
            .put("payload", Maps.of("messages", Maps.of("encoding", "utf8").put("compress", "raw").put("format", "json").put("status", 3).put("text", textBase64)))
            .toJSON();
    }


    ///   http://emb-cn-huabei-1.xf-yun.com/
    public static String createEmbedURL(SparkLlmConfig config) {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss '+0000'", Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String date = sdf.format(new Date());

        String header = "host: emb-cn-huabei-1.xf-yun.com\n";
        header += "date: " + date + "\n";
        header += "POST / HTTP/1.1";

        String base64 = HashUtil.hmacSHA256ToBase64(header, config.getApiSecret());
        String authorization_origin = "api_key=\"" + config.getApiKey()
            + "\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"" + base64 + "\"";

        String authorization = Base64.getEncoder().encodeToString(authorization_origin.getBytes());
        return "http://emb-cn-huabei-1.xf-yun.com/?authorization=" + authorization
            + "&date=" + urlEncode(date) + "&host=emb-cn-huabei-1.xf-yun.com";
    }
}
