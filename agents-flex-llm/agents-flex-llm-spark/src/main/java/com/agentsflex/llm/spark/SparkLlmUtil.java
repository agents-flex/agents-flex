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

import com.agentsflex.functions.Function;
import com.agentsflex.functions.Parameter;
import com.agentsflex.llm.ChatOptions;
import com.agentsflex.message.MessageStatus;
import com.agentsflex.parser.AiMessageParser;
import com.agentsflex.parser.FunctionMessageParser;
import com.agentsflex.parser.impl.DefaultAiMessageParser;
import com.agentsflex.parser.impl.DefaultFunctionMessageParser;
import com.agentsflex.prompt.DefaultPromptFormat;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.prompt.PromptFormat;
import com.agentsflex.util.HashUtil;
import com.agentsflex.util.Maps;
import com.alibaba.fastjson.JSON;

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
                        propertiesMap.put(parameter.getName(), Maps.of("type", parameter.getType()).put("description", parameter.getDescription()).build());
                    }
                }

                Maps.Builder builder = Maps.of("name", function.getName()).put("description", function.getDescription())
                    .put("parameters", Maps.of("type", "object").put("properties", propertiesMap).put("required", requiredProperties));
                functionsJsonArray.add(builder.build());
            }
        }
    };


    public static AiMessageParser getAiMessageParser() {
        DefaultAiMessageParser aiMessageParser = new DefaultAiMessageParser();
        aiMessageParser.setContentPath("$.payload.choices.text[0].content");
        aiMessageParser.setIndexPath("$.payload.choices.text[0].index");
        aiMessageParser.setStatusPath("$.payload.choices.status");
        aiMessageParser.setStatusParser(content -> parseMessageStatus((Integer) content));
        return aiMessageParser;
    }


    public static FunctionMessageParser getFunctionMessageParser() {
        DefaultFunctionMessageParser functionMessageParser = new DefaultFunctionMessageParser();
        functionMessageParser.setFunctionNamePath("$.payload.choices.text[0].function_call.name");
        functionMessageParser.setFunctionArgsPath("$.payload.choices.text[0].function_call.arguments");
        functionMessageParser.setFunctionArgsParser(JSON::parseObject);
        return functionMessageParser;
    }


    public static String promptToPayload(Prompt prompt, SparkLlmConfig config, ChatOptions options) {
        // https://www.xfyun.cn/doc/spark/Web.html#_1-%E6%8E%A5%E5%8F%A3%E8%AF%B4%E6%98%8E
        Maps.Builder root = Maps.of("header", Maps.of("app_id", config.getAppId()).put("uid", UUID.randomUUID()));
        root.put("parameter", Maps.of("chat", Maps.of("domain", getDomain(config.getVersion()))
            .putIf(options.getTemperature() > 0, "temperature", options.getTemperature())
            .putIf(options.getMaxTokens() > 0, "max_tokens", options.getMaxTokens())));
        root.put("payload", Maps.of("message", Maps.of("text", promptFormat.toMessagesJsonObject(prompt)))
            .putIfNotEmpty("functions", Maps.ofNotNull("text", promptFormat.toFunctionsJsonObject(prompt)))
        );
        return JSON.toJSONString(root.build());
    }

    public static MessageStatus parseMessageStatus(Integer status) {
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
            default:
                return "general";
        }
    }
}
