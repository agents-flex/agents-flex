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
package com.agentsflex.llm.tencent;

import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.message.*;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.prompt.DefaultPromptFormat;
import com.agentsflex.core.prompt.ImagePrompt;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.PromptFormat;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.MessageUtil;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSONPath;
import com.tencentcloudapi.common.DatatypeConverter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

public class TencentLlmUtil {

    private static final PromptFormat promptFormat = new DefaultPromptFormat() {
        @Override
        protected void buildMessageContent(Message message, Map<String, Object> map) {
            map.clear();
            if (message instanceof HumanMessage) {
                map.put("Role", "user");
            } else if (message instanceof AiMessage) {
                map.put("Role", "assistant");
                map.put("Content", "");
                AiMessage aiMessage = (AiMessage) message;
                List<FunctionCall> calls = aiMessage.getCalls();
                if (calls != null && !calls.isEmpty()) {
                    buildToolCalls(map, calls);
                    return;
                }
            } else if (message instanceof SystemMessage) {
                map.put("Role", "system");
            } else if (message instanceof ToolMessage) {
                map.put("Role", "tool");
                map.put("Tool_call_id", ((ToolMessage) message).getToolCallId());
            }
            if (message instanceof HumanImageMessage) {
                ImagePrompt prompt = ((HumanImageMessage) message).getPrompt();
                List<Map<String, Object>> list = new ArrayList<>();
                list.add(Maps.of("Type", "image_url").set("Text", prompt.getContent()).set("ImageUrl", Maps.of("Url", prompt.toUrl())));
                map.put("Contents", list);
            } else {
                map.put("Content", message.getMessageContent());
            }
        }

        @Override
        protected Object buildToolCallsArguments(Map<String, Object> arguments) {
            return arguments;
        }
    };

    private final static Charset UTF8 = StandardCharsets.UTF_8;
    private final static String CT_JSON = "application/json; charset=utf-8";

    public static byte[] hmac256(byte[] key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key, mac.getAlgorithm());
        mac.init(secretKeySpec);
        return mac.doFinal(msg.getBytes(UTF8));
    }

    public static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes(UTF8));
        return DatatypeConverter.printHexBinary(d).toLowerCase();
    }

    public static Map<String, String> createAuthorizationToken(TencentLlmConfig config, String action, String payload) {
        try {
            String service = config.getService();
            String host = config.getHost();
            String version = "2023-09-01";
            String algorithm = "TC3-HMAC-SHA256";
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            // 注意时区，否则容易出错
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String date = sdf.format(new Date(Long.parseLong(timestamp + "000")));

            // ************* 步骤 1：拼接规范请求串 *************
            String httpRequestMethod = "POST";
            String canonicalUri = "/";
            String canonicalQueryString = "";
            String canonicalHeaders = "content-type:application/json; charset=utf-8\n"
                + "host:" + host + "\n" + "x-tc-action:" + action.toLowerCase() + "\n";
            String signedHeaders = "content-type;host;x-tc-action";

            String hashedRequestPayload = sha256Hex(payload);
            String canonicalRequest = httpRequestMethod + "\n" + canonicalUri + "\n" + canonicalQueryString + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + hashedRequestPayload;
            System.out.println(canonicalRequest);

            // ************* 步骤 2：拼接待签名字符串 *************
            String credentialScope = date + "/" + service + "/" + "tc3_request";
            String hashedCanonicalRequest = sha256Hex(canonicalRequest);
            String stringToSign = algorithm + "\n" + timestamp + "\n" + credentialScope + "\n" + hashedCanonicalRequest;
            System.out.println(stringToSign);

            // ************* 步骤 3：计算签名 *************
            byte[] secretDate = hmac256(("TC3" + config.getApiKey()).getBytes(UTF8), date);
            byte[] secretService = hmac256(secretDate, service);
            byte[] secretSigning = hmac256(secretService, "tc3_request");
            String signature = DatatypeConverter.printHexBinary(hmac256(secretSigning, stringToSign)).toLowerCase();
            System.out.println(signature);

            // ************* 步骤 4：拼接 Authorization *************
            String authorization = algorithm + " " + "Credential=" + config.getApiSecret() + "/" + credentialScope + ", "
                + "SignedHeaders=" + signedHeaders + ", " + "Signature=" + signature;
            System.out.println(authorization);

            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", authorization);
            headers.put("Content-Type", CT_JSON);
            headers.put("Host", host);
            headers.put("X-TC-Action", action);
            headers.put("X-TC-Timestamp", timestamp);
            headers.put("X-TC-Version", version);
            return headers;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static AiMessageParser getAiMessageParser(boolean isStream) {
        DefaultAiMessageParser aiMessageParser = new DefaultAiMessageParser();
        String data;
        if (isStream) {
            data = "";
        } else {
            data = "Response.";
        }
        aiMessageParser.setIndexPath("$." + data + "choices[0].Index");
        if (isStream) {
            aiMessageParser.setContentPath("$." + data + "Choices[0].Delta.Content");
        } else {
            aiMessageParser.setContentPath("$." + data + "Choices[0].Message.Content");
        }
        aiMessageParser.setTotalTokensPath("$." + data + "Usage.TotalTokens");
        aiMessageParser.setCompletionTokensPath("$." + data + "Usage.CompletionTokens");
        aiMessageParser.setPromptTokensPath("$." + data + "Usage.PromptTokens");
        aiMessageParser.setStatusParser(content -> {
            String done = (String) JSONPath.eval(content, "$." + data + "Choices[0].FinishReason");
            if (StringUtil.hasText(done)) {
                return MessageStatus.END;
            }
            return MessageStatus.MIDDLE;
        });
        return aiMessageParser;
    }


    public static String promptToPayload(Prompt prompt, TencentLlmConfig config, boolean withStream, ChatOptions options) {
        List<Message> messages = prompt.toMessages();
        HumanMessage message = MessageUtil.findLastHumanMessage(messages);
        return Maps.of("Model", Optional.ofNullable(options.getModel()).orElse(config.getModel()))
            .set("Messages", promptFormat.toMessagesJsonObject(messages))
            .setIf(withStream, "Stream", withStream)
            .setIfNotEmpty("Tools", promptFormat.toFunctionsJsonObject(message))
            .setIfContainsKey("Tools", "ToolChoice", MessageUtil.getToolChoice(message))
            .setIfNotNull("top_p", options.getTopP())
            .setIfNotEmpty("Stop", options.getStop())
            .setIf(map -> !map.containsKey("tools") && options.getMaxTokens() != null, "max_tokens", options.getMaxTokens())
            .setIfNotEmpty(options.getExtra())
            .toJSON();
    }


}
