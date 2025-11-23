/*
 * Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentsflex.core.model.client;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatConfig;
import com.agentsflex.core.model.chat.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * 将内部消息模型序列化为大模型（如 OpenAI）可识别的请求格式。
 * 虽然名为 ChatMessageSerializer，但同时也支持序列化工具/函数定义，
 * 因为这些通常是聊天请求的一部分（如 tools 或 functions 字段）。
 */
public interface ChatMessageSerializer {

    /**
     * 将消息列表序列化为模型所需的聊天消息数组格式。
     * 例如 OpenAI 的 [{"role": "user", "content": "..."}, ...]
     *
     * @param messages 消息列表，不可为 null
     * @return 序列化后的消息数组，若输入为空则返回空列表
     */
    List<Map<String, Object>> serializeMessages(List<Message> messages, ChatConfig config);

    /**
     * 将函数定义列表序列化为模型所需的工具（tools）或函数（functions）格式。
     * 例如 OpenAI 的 [{"type": "function", "function": {...}}, ...]
     *
     * @param tools 函数定义列表，可能为 null 或空
     * @return 序列化后的函数定义数组，若输入为空则返回空列表
     */
    List<Map<String, Object>> serializeTools(List<Tool> tools, ChatConfig config);

    default List<Map<String, Object>> serializeTools(UserMessage userMessage, ChatConfig config) {
        return serializeTools(userMessage == null ? null : userMessage.getTools(), config);
    }
}
