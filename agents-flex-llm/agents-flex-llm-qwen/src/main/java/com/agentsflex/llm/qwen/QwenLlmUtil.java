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
package com.agentsflex.llm.qwen;

import com.agentsflex.message.AiMessage;
import com.agentsflex.message.HumanMessage;
import com.agentsflex.message.Message;
import com.agentsflex.message.MessageStatus;
import com.agentsflex.prompt.Prompt;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QwenLlmUtil {


    public static AiMessage parseAiMessage(String json, int length) {
        AiMessage aiMessage = new AiMessage();
        JSONObject jsonObject = JSON.parseObject(json);
        MessageStatus messageStatus = parseMessageStatus((String) JSONPath.eval(json, "$.output.finish_reason"));
        aiMessage.setStatus(messageStatus);

        String text = (String) JSONPath.eval(jsonObject, "$.output.text");
        aiMessage.setContent(text.substring(length));
        aiMessage.setTotalTokens((Integer) JSONPath.eval(jsonObject, "$.usage.total_tokens"));
        return aiMessage;
    }


    public static MessageStatus parseMessageStatus(String status) {
        return "stop".equals(status) ? MessageStatus.END : MessageStatus.MIDDLE;
    }


    public static String promptToPayload(Prompt prompt, QwenLlmConfig config) {

        List<Message> messages = prompt.getMessages();

        // https://help.aliyun.com/zh/dashscope/developer-reference/api-details?spm=a2c4g.11186623.0.0.1ff6fa70jCgGRc#b8ebf6b25eul6
        String payload = "{\n" +
            "  \"model\": \"" + config.getModel() + "\",\n" +
            "  \"input\": {\n" +
            "    \"messages\": messageJsonString\n" +
            "  }\n" +
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
