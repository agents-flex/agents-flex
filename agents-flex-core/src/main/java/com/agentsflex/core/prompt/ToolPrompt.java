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

import com.agentsflex.core.llm.response.AiMessageResponse;
import com.agentsflex.core.llm.response.FunctionCaller;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;

import java.util.ArrayList;
import java.util.List;

public class ToolPrompt extends Prompt {

    private List<Message> messages;

    public static ToolPrompt of(AiMessageResponse response) {
        return of(response, null);
    }


    public static ToolPrompt of(AiMessageResponse response, HistoriesPrompt withHistories) {
        List<FunctionCaller> functionCallers = response.getFunctionCallers();
        List<ToolMessage> toolMessages = new ArrayList<>(functionCallers.size());

        for (FunctionCaller functionCaller : functionCallers) {
            ToolMessage toolMessage = new ToolMessage();
            toolMessage.setToolCallId(functionCaller.getFunctionCall().getId());
            if (StringUtil.noText(toolMessage.getToolCallId())) {
                toolMessage.setToolCallId(functionCaller.getFunctionCall().getName());
            }
            Object object = functionCaller.call();
            if (object instanceof CharSequence || object instanceof Number) {
                toolMessage.setContent(object.toString());
            } else {
                toolMessage.setContent(JSON.toJSONString(object));
            }
            toolMessages.add(toolMessage);
        }


        if (withHistories != null) {
            withHistories.addMessages(response.getPrompt().toMessages());
            withHistories.addMessage(response.getMessage());
            for (ToolMessage toolMessage : toolMessages) {
                withHistories.addMessage(toolMessage);
            }
            return new HistoriesToolPrompt(withHistories);
        } else {
            ToolPrompt toolPrompt = new ToolPrompt();
            toolPrompt.messages = new ArrayList<>();
            toolPrompt.messages.addAll(response.getPrompt().toMessages());
            toolPrompt.messages.add(response.getMessage());
            toolPrompt.messages.addAll(toolMessages);
            return toolPrompt;
        }
    }

    static class HistoriesToolPrompt extends ToolPrompt {
        HistoriesPrompt historiesPrompt;

        public HistoriesToolPrompt(HistoriesPrompt historiesPrompt) {
            this.historiesPrompt = historiesPrompt;
        }

        @Override
        public List<Message> toMessages() {
            return historiesPrompt.toMessages();
        }
    }


    @Override
    public List<Message> toMessages() {
        return messages;
    }
}
