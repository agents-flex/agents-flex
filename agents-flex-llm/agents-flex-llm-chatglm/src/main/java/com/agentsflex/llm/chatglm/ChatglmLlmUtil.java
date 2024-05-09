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
package com.agentsflex.llm.chatglm;

import com.agentsflex.message.AiMessage;
import com.agentsflex.message.MessageStatus;
import com.agentsflex.parser.AiMessageParser;
import com.agentsflex.parser.FunctionMessageParser;
import com.agentsflex.parser.impl.BaseAiMessageParser;
import com.agentsflex.parser.impl.BaseFunctionMessageParser;
import com.agentsflex.prompt.DefaultPromptFormat;
import com.agentsflex.prompt.Prompt;
import com.agentsflex.prompt.PromptFormat;
import com.agentsflex.util.Maps;
import com.alibaba.fastjson.JSON;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.impl.DefaultJwtBuilder;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class ChatglmLlmUtil {

    private static final PromptFormat promptFormat = new DefaultPromptFormat();

    public static class MyJwtBuilder extends DefaultJwtBuilder {
        @Override
        protected String base64UrlEncode(Object o, String errMsg) {
            byte[] bytes;
            try {
                bytes = toJson(o);
            } catch (Exception e) {
                throw new IllegalStateException(errMsg, e);
            }
            return Base64.getUrlEncoder().encodeToString(bytes);
        }
    }


    public static String createAuthorizationToken(ChatglmLlmConfig config) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("alg", "HS256");
        headers.put("sign_type", "SIGN");

        long nowMillis = System.currentTimeMillis();
        String[] idAndSecret = config.getApiKey().split("\\.");

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("api_key", idAndSecret[0]);
        payloadMap.put("exp", nowMillis + 3600000);
        payloadMap.put("timestamp", nowMillis);
        String payloadJsonString = JSON.toJSONString(payloadMap);

        byte[] bytes = idAndSecret[1].getBytes();
        JwtBuilder builder = new MyJwtBuilder()
            .setPayload(payloadJsonString)
            .setHeader(headers)
            .signWith(SignatureAlgorithm.HS256, bytes);
        return builder.compact();
    }

    public static AiMessageParser getAiMessageParser() {
        BaseAiMessageParser aiMessageParser = new BaseAiMessageParser() {
            @Override
            public AiMessage parse(String content) {
                if ("[DONE]".equals(content)) {
                    return null;
                }
                return super.parse(content);
            }
        };
        aiMessageParser.setContentPath("$.choices[0].delta.content");
        aiMessageParser.setIndexPath("$.choices[0].index");
        aiMessageParser.setStatusPath("$.choices[0].finish_reason");
        aiMessageParser.setStatusParser(content -> parseMessageStatus((String) content));
        aiMessageParser.setTotalTokensPath("$.usage.total_tokens");
        return aiMessageParser;
    }


    public static FunctionMessageParser getFunctionMessageParser() {
        BaseFunctionMessageParser functionMessageParser = new BaseFunctionMessageParser();
        functionMessageParser.setFunctionNamePath("$.choices[0].message.tool_calls[0].function.name");
        functionMessageParser.setFunctionArgsPath("$.choices[0].message.tool_calls[0].function.arguments");
        functionMessageParser.setFunctionArgsParser(JSON::parseObject);
        return functionMessageParser;
    }


    public static String promptToPayload(Prompt prompt, ChatglmLlmConfig config, boolean stream) {
        Maps.Builder builder = Maps.of("model", config.getModel())
            .put("messages", promptFormat.toMessagesJsonObject(prompt))
            .putIf(stream, "stream", stream)
            .putIfNotEmpty("tools", promptFormat.toFunctionsJsonObject(prompt));
        return JSON.toJSONString(builder.build());

    }


    public static MessageStatus parseMessageStatus(String status) {
        return "stop".equals(status) ? MessageStatus.END : MessageStatus.MIDDLE;
    }


}
