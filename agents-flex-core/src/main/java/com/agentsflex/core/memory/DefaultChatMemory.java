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
package com.agentsflex.core.memory;

import com.agentsflex.core.message.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DefaultChatMemory implements ChatMemory {
    private final Object id;
    private final List<Message> messages = new ArrayList<>();

    public DefaultChatMemory() {
        this.id = UUID.randomUUID().toString();
    }

    public DefaultChatMemory(Object id) {
        this.id = id;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public List<Message> getMessages(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be greater than 0");
        }
        if (count >= messages.size()) {
            // 返回副本，避免外部修改污染内部状态
            return new ArrayList<>(messages);
        } else {
            // 同样返回副本：subList() 返回的是原列表视图，
            // 调用方对其 set/add/remove 会直接修改内部 messages
            return new ArrayList<>(messages.subList(messages.size() - count, messages.size()));
        }
    }

    @Override
    public void addMessage(Message message) {
        messages.add(message);
    }

    @Override
    public void clear() {
        messages.clear();
    }


}
