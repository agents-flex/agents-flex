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
package com.agentsflex.memory;

import com.agentsflex.message.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DefaultChatMemory implements ChatMemory {

    private final Object id;

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
    private final List<Message> messages = new ArrayList<>();

    @Override
    public List<Message> getMessages() {
        return messages;
    }

    @Override
    public void addMessage(Message message) {
        messages.add(message);
    }


}
