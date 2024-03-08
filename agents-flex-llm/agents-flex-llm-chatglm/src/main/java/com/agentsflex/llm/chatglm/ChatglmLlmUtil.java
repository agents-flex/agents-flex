package com.agentsflex.llm.chatglm;

import com.agentsflex.message.AiMessage;
import com.agentsflex.message.HumanMessage;
import com.agentsflex.message.Message;
import com.agentsflex.message.MessageStatus;
import com.agentsflex.prompt.Prompt;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import java.util.*;

public class ChatglmLlmUtil {

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

        JwtBuilder builder = Jwts.builder()
            .setPayload(payloadJsonString)
            .setHeader(headers)
            .signWith(SignatureAlgorithm.HS256, idAndSecret[1].getBytes());

        return builder.compact();
    }


    public static String promptToPayload(Prompt prompt, ChatglmLlmConfig config) {

        List<Message> messages = prompt.toMessages();

        // https://open.bigmodel.cn/dev/api#glm-4
        String payload = "{\n" +
            "  \"model\": \"" + config.getModel() + "\",\n" +
            "  \"messages\": messageJsonString\n" +
            "}";


        List<Map<String, String>> messageArray = new ArrayList<>();
        messages.forEach(message -> {
            Map<String, String> map = new HashMap<>(2);
            if (message instanceof HumanMessage) {
                map.put("role", "user");
                map.put("content", ((HumanMessage) message).getContent());
            } else if (message instanceof AiMessage) {
                map.put("role", "assistant");
                map.put("content", ((AiMessage) message).getFullContent());
            }

            messageArray.add(map);
        });

        String messageText = JSON.toJSONString(messageArray);
        return payload.replace("messageJsonString", messageText);
    }


    public static AiMessage parseAiMessage(String json) {
        AiMessage aiMessage = new AiMessage();
        JSONObject jsonObject = JSON.parseObject(json);
        Object status = JSONPath.eval(jsonObject, "$.choices[0].finish_reason");
        MessageStatus messageStatus = parseMessageStatus((String) status);
        aiMessage.setStatus(messageStatus);
        aiMessage.setIndex((Integer) JSONPath.eval(jsonObject, "$.choices[0].index"));
        aiMessage.setContent((String) JSONPath.eval(jsonObject, "$.choices[0].message.content"));
        aiMessage.setTotalTokens((Integer)JSONPath.eval(jsonObject,"$.usage.total_tokens"));
        return aiMessage;
    }

    public static MessageStatus parseMessageStatus(String status) {
        return "stop".equals(status) ? MessageStatus.END : MessageStatus.MIDDLE;
    }


}
