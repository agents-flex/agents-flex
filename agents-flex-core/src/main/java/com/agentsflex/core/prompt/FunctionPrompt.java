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
import com.agentsflex.core.functions.Functions;
import com.agentsflex.core.llm.response.FunctionMessageResponse;
import com.agentsflex.core.memory.ChatMemory;
import com.agentsflex.core.memory.DefaultChatMemory;
import com.agentsflex.core.message.HumanMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class FunctionPrompt extends Prompt<FunctionMessageResponse> {

    private SystemMessage systemMessage;
    private final ChatMemory memory = new DefaultChatMemory();
    private final List<Function> functions = new ArrayList<>();

    public FunctionPrompt(String prompt, Collection<Function> functions) {
        memory.addMessage(new HumanMessage(prompt));
        this.functions.addAll(functions);
    }

    public FunctionPrompt(List<Message> messages, Collection<Function> functions) {
        memory.addMessages(messages);
        this.functions.addAll(functions);
    }

    public FunctionPrompt(String prompt, Class<?> funcClass, String... methodNames) {
        memory.addMessage(new HumanMessage(prompt));
        functions.addAll(Functions.from(funcClass, methodNames));
    }

    public FunctionPrompt(List<Message> messages, Class<?> funcClass, String... methodNames) {
        memory.addMessages(messages);
        functions.addAll(Functions.from(funcClass, methodNames));
    }

    public FunctionPrompt(String prompt, Object funcObject, String... methodNames) {
        memory.addMessage(new HumanMessage(prompt));
        functions.addAll(Functions.from(funcObject, methodNames));
    }

    public FunctionPrompt(List<Message> messages, Object funcObject, String... methodNames) {
        memory.addMessages(messages);
        functions.addAll(Functions.from(funcObject, methodNames));
    }

    public SystemMessage getSystemMessage() {
        return systemMessage;
    }

    public void setSystemMessage(SystemMessage systemMessage) {
        this.systemMessage = systemMessage;
    }

    public ChatMemory getMemory() {
        return memory;
    }

    @Override
    public List<Message> toMessages() {
        List<Message> messages = memory.getMessages();
        if (systemMessage != null){
            messages.add(0, systemMessage);
        }
        return messages;
    }

    public List<Function> getFunctions() {
        return functions;
    }
}
