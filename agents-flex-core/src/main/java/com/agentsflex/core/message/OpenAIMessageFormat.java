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
package com.agentsflex.core.message;

import com.agentsflex.core.model.chat.functions.Function;
import com.agentsflex.core.model.chat.functions.Parameter;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenAIMessageFormat implements MessageFormat {

    @Override
    public Object toMessagesJsonObject(List<Message> messages) {
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
            if (message instanceof UserMessage) {
                map.put("role", "user");
            } else if (message instanceof AiMessage) {
                map.put("role", "assistant");
                AiMessage aiMessage = (AiMessage) message;
                List<FunctionCall> calls = aiMessage.getCalls();
                if (calls != null && !calls.isEmpty()) {
                    map.put("content", ""); // 清空 content，在某模型下，会把思考的部分当做 content 的部分
                    buildToolCalls(map, calls);
                    //不需要 build content 了
                    messageJsonArray.add(map);
                    return;
                }
            } else if (message instanceof SystemMessage) {
                map.put("role", "system");
            } else if (message instanceof ToolMessage) {
                map.put("role", "tool");
                map.put("tool_call_id", ((ToolMessage) message).getToolCallId());
            }
            buildMessageContent(message, map);
            messageJsonArray.add(map);
        });
    }

    protected void buildToolCalls(Map<String, Object> map, List<FunctionCall> calls) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (FunctionCall call : calls) {
            Maps toolCall = new Maps();
            toolCall.set("id", call.getId())
                .set("type", "function")
                .set("function", Maps.of("name", call.getName())
                    .set("arguments", buildToolCallsArguments(call.getArgs()))
                );

            toolCalls.add(toolCall);
        }
        map.put("tool_calls", toolCalls);
    }

    protected Object buildToolCallsArguments(Map<String, Object> arguments) {
//        return JSON.toJSONString(arguments, SerializerFeature.DisableCircularReferenceDetect);
        return JSON.toJSONString(arguments);
    }


    protected void buildMessageContent(Message message, Map<String, Object> map) {
        map.put("content", message.getMessageContent());
    }

    @Override
    public Object toFunctionsJsonObject(UserMessage message) {
        if (message == null) {
            return null;
        }
        List<Function> functions = message.getFunctions();
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

            addParameters(function.getParameters(), propertiesObj, parametersObj);

            functionsJsonArray.add(functionRoot);
        }
    }

    private static void addParameters(Parameter[] parameters, Map<String, Object> propertiesObj, Map<String, Object> parametersObj) {
        List<String> requiredProperties = new ArrayList<>();
        for (Parameter parameter : parameters) {
            Map<String, Object> parameterObj = new HashMap<>();
            parameterObj.put("type", parameter.getType());
            parameterObj.put("description", parameter.getDescription());
            parameterObj.put("enum", parameter.getEnums());
            if (parameter.isRequired()) {
                requiredProperties.add(parameter.getName());
            }

            List<Parameter> children = parameter.getChildren();
            if (children != null && !children.isEmpty()) {
                if ("object".equalsIgnoreCase(parameter.getType())) {
                    Map<String, Object> childrenObj = new HashMap<>();
                    parameterObj.put("properties", childrenObj);
                    addParameters(children.toArray(new Parameter[0]), childrenObj, parameterObj);
                }
                if ("array".equalsIgnoreCase(parameter.getType())) {
                    Map<String, Object> itemsObj = new HashMap<>();
                    parameterObj.put("items", itemsObj);
                    handleArrayItems(children, itemsObj);
                }
            }

            propertiesObj.put(parameter.getName(), parameterObj);
        }

        if (!requiredProperties.isEmpty()) {
            parametersObj.put("required", requiredProperties);
        }
    }

    private static void handleArrayItems(List<Parameter> children, Map<String, Object> itemsObj) {
        if (children.size() == 1 && children.get(0).getName() == null) {
            // 单值数组，数组元素是基础类型
            Parameter firstChild = children.get(0);
            itemsObj.put("type", firstChild.getType());
            itemsObj.put("description", firstChild.getDescription());
            itemsObj.put("enum", firstChild.getEnums());
            // 如果基础类型本身也是数组，需要递归处理
            List<Parameter> grandchildren = firstChild.getChildren();
            if (grandchildren != null && !grandchildren.isEmpty()) {
                if ("array".equalsIgnoreCase(firstChild.getType())) {
                    Map<String, Object> nestedItemsObj = new HashMap<>();
                    itemsObj.put("items", nestedItemsObj);
                    handleArrayItems(grandchildren, nestedItemsObj);
                } else if ("object".equalsIgnoreCase(firstChild.getType())) {
                    Map<String, Object> nestedProperties = new HashMap<>();
                    itemsObj.put("properties", nestedProperties);
                    addParameters(grandchildren.toArray(new Parameter[0]), nestedProperties, itemsObj);
                }
            }
        } else {
            // 复杂数组，数组元素是对象或其他复杂类型
            Map<String, Object> tempProperties = new HashMap<>();
            addParameters(children.toArray(new Parameter[0]), tempProperties, itemsObj);

            if (!tempProperties.isEmpty()) {
                itemsObj.put("type", "object");
                itemsObj.put("properties", tempProperties);
            }
        }
    }
}
