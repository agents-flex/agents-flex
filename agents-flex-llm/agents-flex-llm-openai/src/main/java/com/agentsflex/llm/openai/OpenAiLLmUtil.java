package com.agentsflex.llm.openai;

import com.agentsflex.message.AiMessage;
import com.agentsflex.message.HumanMessage;
import com.agentsflex.message.Message;
import com.agentsflex.prompt.Prompt;
import com.alibaba.fastjson.JSON;

import java.util.*;

public class OpenAiLLmUtil {

    public static AiMessage parseAiMessage(String json){
        return null;
    }


    public static String promptToPayload(Prompt prompt, OpenAiLlmConfig config) {

        List<Message> messages = prompt.toMessages();

        // https://platform.openai.com/docs/api-reference/making-requests
        String payload = "{\n" +
//            "  \"model\": \"gpt-3.5-turbo\",\n" +
            "  \"model\": \""+config.getModel()+"\",\n" +
            "  \"messages\": messageJsonString,\n" +
            "  \"temperature\": 0.7\n" +
            "}";


        List<Map<String, String>> messageArray = new ArrayList<>();
        messages.forEach(message -> {
            Map<String, String> map = new HashMap<>(2);
            if (message instanceof HumanMessage) {
                map.put("role", "user");
                map.put("content", message.getContent());
            } else if (message instanceof AiMessage) {
                map.put("role", "assistant");
                map.put("content", ((AiMessage) message).getFullContent());
            }

            messageArray.add(map);
        });

        String messageText = JSON.toJSONString(messageArray);
        return payload.replace("messageJsonString", messageText);
    }


}
