/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.llm.openai;

import com.agentsflex.message.AiMessage;
import com.agentsflex.message.HumanMessage;
import com.agentsflex.message.Message;
import com.agentsflex.message.MessageStatus;
import com.agentsflex.prompt.Prompt;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.*;

public class OpenAiLLmUtil {

    public static AiMessage parseAiMessage(String json) {
        AiMessage aiMessage = new AiMessage();
        JSONObject jsonObject = JSON.parseObject(json);
        Object status = JSONPath.eval(jsonObject, "$.choices[0].finish_reason");
        MessageStatus messageStatus = parseMessageStatus((String) status);
        aiMessage.setStatus(messageStatus);
        aiMessage.setIndex((Integer) JSONPath.eval(jsonObject, "$.choices[0].index"));
        aiMessage.setContent((String) JSONPath.eval(jsonObject, "$.choices[0].delta.content"));
        return aiMessage;
    }

    public static MessageStatus parseMessageStatus(String status) {
        return "stop".equals(status) ? MessageStatus.END : MessageStatus.MIDDLE;
    }


    public static String promptToPayload(Prompt prompt, OpenAiLlmConfig config) {

        List<Message> messages = prompt.toMessages();

        // https://platform.openai.com/docs/api-reference/making-requests
        String payload = "{\n" +
//            "  \"model\": \"gpt-3.5-turbo\",\n" +
            "  \"model\": \"" + config.getModel() + "\",\n" +
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
