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
package com.agentsflex.core.memory;

import com.agentsflex.core.message.Message;

import java.util.Collection;
import java.util.List;

public interface ChatMemory extends Memory {
    List<Message> getMessages();

    void addMessage(Message message);

    default void addMessages(Collection<Message> messages){
        for (Message message : messages) {
            addMessage(message);
        }
    }
}
