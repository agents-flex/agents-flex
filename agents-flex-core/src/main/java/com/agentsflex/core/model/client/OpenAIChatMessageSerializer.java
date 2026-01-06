/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.core.model.client;

import com.agentsflex.core.message.*;
import com.agentsflex.core.model.chat.ChatConfig;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.util.CollectionUtil;
import com.agentsflex.core.util.ImageUtil;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson2.JSON;

import java.util.*;

public class OpenAIChatMessageSerializer implements ChatMessageSerializer {

    /**
     * 将消息列表序列化为模型所需的聊天消息数组格式。
     * 例如 OpenAI 的 [{"role": "user", "content": "..."}, ...]
     *
     * @param messages 消息列表，不可为 null
     * @return 序列化后的消息数组，若输入为空则返回空列表
     */
    @Override
    public List<Map<String, Object>> serializeMessages(List<Message> messages, ChatConfig config) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        return buildMessageList(messages, config);
    }

    protected List<Map<String, Object>> buildMessageList(List<Message> messages, ChatConfig config) {
        List<Map<String, Object>> messageList = new ArrayList<>(messages.size());
        messages.forEach(message -> {
            Map<String, Object> objectMap = new HashMap<>(2);
            if (message instanceof UserMessage) {
                buildUserMessageObject(objectMap, (UserMessage) message, config);
            } else if (message instanceof AiMessage) {
                buildAIMessageObject(objectMap, (AiMessage) message, config);
            } else if (message instanceof SystemMessage) {
                buildSystemMessageObject(objectMap, (SystemMessage) message, config);
            } else if (message instanceof ToolMessage) {
                buildToolMessageObject(objectMap, (ToolMessage) message, config);
            }
            messageList.add(objectMap);
        });
        return messageList;
    }

    protected void buildToolMessageObject(Map<String, Object> objectMap, ToolMessage message, ChatConfig config) {
        if (config.isSupportToolMessage()) {
            objectMap.put("role", "tool");
            objectMap.put("content", message.getTextContent());
            objectMap.put("tool_call_id", message.getToolCallId());
        }
        // 部分模型（如 DeepSeek V3）不支持原生 tool message 格式，
        // 此处将 tool message 转换为 system message 格式以确保兼容性
        else {
            objectMap.put("role", "system");
            Map<String, Object> contentMap = new LinkedHashMap<>();
            contentMap.put("tool_call_id", message.getToolCallId());
            contentMap.put("content", message.getTextContent());
            objectMap.put("content", JSON.toJSONString(contentMap));
        }
    }

    protected void buildSystemMessageObject(Map<String, Object> objectMap, SystemMessage message, ChatConfig config) {
        objectMap.put("role", "system");
        objectMap.put("content", message.getTextContent());
    }

    protected void buildUserMessageObject(Map<String, Object> objectMap, UserMessage message, ChatConfig config) {
        objectMap.put("role", "user");
        objectMap.put("content", buildUserMessageContent(message, config));
    }

    protected void buildAIMessageObject(Map<String, Object> objectMap, AiMessage message, ChatConfig config) {
        objectMap.put("role", "assistant");
        objectMap.put("content", message.getTextContent());

        List<ToolCall> calls = message.getToolCalls();
        if (calls != null && !calls.isEmpty()) {
            if (config.isSupportToolMessage()) {
                objectMap.put("content", ""); // 清空 content，在某模型下，会把思考的部分当做 content 的部分
                buildAIMessageToolCalls(objectMap, calls, false);
            } else {
                objectMap.put("role", "system");
                buildAIMessageToolCalls(objectMap, calls, true);
            }
        }
    }

    protected void buildAIMessageToolCalls(Map<String, Object> objectMap, List<ToolCall> calls, boolean buildToContent) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (ToolCall call : calls) {
            Maps toolCall = new Maps();
            toolCall.set("id", call.getId())
                .set("type", "function")
                .set("function", Maps.of("name", call.getName())
                    .set("arguments", call.getArguments())
                );
            toolCalls.add(toolCall);
        }

        if (buildToContent) {
            objectMap.put("content", JSON.toJSONString(toolCalls));
        } else {
            objectMap.put("tool_calls", toolCalls);
        }
    }


    protected Object buildUserMessageContent(UserMessage userMessage, ChatConfig config) {
        String content = userMessage.getTextContent();
        List<String> imageUrls = userMessage.getImageUrls();
        List<String> audioUrls = userMessage.getAudioUrls();
        List<String> videoUrls = userMessage.getVideoUrls();

        if (CollectionUtil.hasItems(imageUrls) || CollectionUtil.hasItems(audioUrls) || CollectionUtil.hasItems(videoUrls)) {

            List<Map<String, Object>> messageContent = new ArrayList<>();
            messageContent.add(Maps.of("type", "text").set("text", content));

            if (CollectionUtil.hasItems(imageUrls)) {
                for (String url : imageUrls) {
                    if (config.isSupportImageBase64Only()
                        && url.toLowerCase().startsWith("http")) {
                        url = ImageUtil.imageUrlToDataUri(url);
                    }
                    messageContent.add(Maps.of("type", "image_url").set("image_url", Maps.of("url", url)));
                }
            }

            if (CollectionUtil.hasItems(audioUrls)) {
                for (String url : audioUrls) {
                    messageContent.add(Maps.of("type", "audio_url").set("audio_url", Maps.of("url", url)));
                }
            }

            if (CollectionUtil.hasItems(videoUrls)) {
                for (String url : videoUrls) {
                    messageContent.add(Maps.of("type", "video_url").set("video_url", Maps.of("url", url)));
                }
            }

            return messageContent;
        } else {
            return content;
        }
    }


    /**
     * 将函数定义列表序列化为模型所需的工具（tools）或函数（functions）格式。
     * 例如 OpenAI 的 [{"type": "function", "function": {...}}, ...]
     *
     * @param tools 函数定义列表，可能为 null 或空
     * @return 序列化后的函数定义数组，若输入为空则返回空列表
     */
    @Override
    public List<Map<String, Object>> serializeTools(List<Tool> tools, ChatConfig config) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        // 大模型不支持 Function Calling
        if (config != null && !config.isSupportTool()) {
            return null;
        }

        return buildToolList(tools);
    }


    protected List<Map<String, Object>> buildToolList(List<Tool> tools) {
        List<Map<String, Object>> functionList = new ArrayList<>();
        for (Tool tool : tools) {
            Map<String, Object> functionRoot = new HashMap<>();
            functionRoot.put("type", "function");

            Map<String, Object> functionObj = new HashMap<>();
            functionRoot.put("function", functionObj);

            functionObj.put("name", tool.getName());
            functionObj.put("description", tool.getDescription());


            Map<String, Object> parametersObj = new HashMap<>();
            functionObj.put("parameters", parametersObj);
            parametersObj.put("type", "object");

            Map<String, Object> propertiesObj = new HashMap<>();
            parametersObj.put("properties", propertiesObj);

            addParameters(tool.getParameters(), propertiesObj, parametersObj);

            functionList.add(functionRoot);
        }

        return functionList;
    }

    protected void addParameters(Parameter[] parameters, Map<String, Object> propertiesObj, Map<String, Object> parametersObj) {
        if (parameters == null || parameters.length == 0) {
            return;
        }
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

    protected void handleArrayItems(List<Parameter> children, Map<String, Object> itemsObj) {
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

