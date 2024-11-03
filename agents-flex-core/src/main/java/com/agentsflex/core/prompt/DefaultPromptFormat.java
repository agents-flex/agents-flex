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
package com.agentsflex.core.prompt;

import com.agentsflex.core.functions.Function;
import com.agentsflex.core.functions.Parameter;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.HumanMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefaultPromptFormat implements PromptFormat {

    @Override
    public Object toMessagesJsonObject(List<Message> messages ) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> messageJsonArray = new ArrayList<>(messages.size());
        buildMessageJsonArray(messageJsonArray, messages);

        return messageJsonArray;
    }

    protected void buildMessageJsonArray(List<Map<String, Object>> messageJsonArray, List<Message> messages) {
        messages.forEach(message -> {
            Map<String, Object> map = new HashMap<>(2);
            if (message instanceof HumanMessage) {
                map.put("role", "user");
            } else if (message instanceof AiMessage) {
                map.put("role", "assistant");
            } else if (message instanceof SystemMessage) {
                map.put("role", "system");
            }
            buildMessageContent(message, map);
            messageJsonArray.add(map);
        });
    }


    protected void buildMessageContent(Message message, Map<String, Object> map) {
        map.put("content", message.getMessageContent());
    }

    @Override
    public Object toFunctionsJsonObject(Message message) {
        if (!(message instanceof HumanMessage)) {
            return null;
        }

        List<Function> functions = ((HumanMessage) message).getFunctions();
        if (functions == null || functions.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> functionsJsonArray = new ArrayList<>();
        buildFunctionJsonArray(functionsJsonArray, functions);

        return functionsJsonArray;
    }

    protected void buildFunctionJsonArray(List<Map<String, Object>> functionsJsonArray, List<Function> functions) {
        for (Function function : functions) {
            Map<String, Object> functionRoot = new HashMap<>();
            functionRoot.put("type", "function");

            Map<String, Object> functionObj = new HashMap<>();
            functionRoot.put("function", functionObj);

            functionObj.put("name", function.getName());
            functionObj.put("description", function.getDescription());


            Map<String, Object> parametersObj = new HashMap<>();
            functionObj.put("parameters", parametersObj);

            parametersObj.put("type", "object");

            Map<String, Object> propertiesObj = new HashMap<>();
            parametersObj.put("properties", propertiesObj);

            List<String> requiredProperties = new ArrayList<>();

            for (Parameter parameter : function.getParameters()) {
                Map<String, Object> parameterObj = new HashMap<>();
                parameterObj.put("type", parameter.getType());
                parameterObj.put("description", parameter.getDescription());
                parameterObj.put("enum", parameter.getEnums());

                if (parameter.isRequired()) {
                    requiredProperties.add(parameter.getName());
                }

                propertiesObj.put(parameter.getName(), parameterObj);
            }

            if (!requiredProperties.isEmpty()) {
                parametersObj.put("required", requiredProperties);
            }

            functionsJsonArray.add(functionRoot);
        }
    }

}
