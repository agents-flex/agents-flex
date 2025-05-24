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

    protected List<Message> messages;

    public static ToolPrompt of(AiMessageResponse response) {
        return of(response, null);
    }


    public static ToolPrompt of(AiMessageResponse response, HistoriesPrompt withHistories) {
        List<FunctionCaller> functionCallers = response.getFunctionCallers();
        List<Message> toolMessages = new ArrayList<>(functionCallers.size());

        for (FunctionCaller functionCaller : functionCallers) {
            ToolMessage toolMessage = new ToolMessage();
            String callId = functionCaller.getFunctionCall().getId();
            if (StringUtil.hasText(callId)) {
                toolMessage.setToolCallId(callId);
            } else {
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
            withHistories.addMessages(toolMessages);
            return new HistoriesToolPrompt(withHistories);
        } else {
            ToolPrompt toolPrompt = new ToolPrompt();
            toolPrompt.messages = new ArrayList<>();

            //用户问题
            toolPrompt.messages.addAll(response.getPrompt().toMessages());

            // 模型返回
            toolPrompt.messages.add(response.getMessage());

            // 执行结果
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

        @Override
        public String toString() {
            return "HistoriesToolPrompt{" +
                "historiesPrompt=" + historiesPrompt +
                ", messages=" + messages +
                ", metadataMap=" + metadataMap +
                '}';
        }
    }


    @Override
    public List<Message> toMessages() {
        return messages;
    }

    @Override
    public String toString() {
        return "ToolPrompt{" +
            "messages=" + messages +
            ", metadataMap=" + metadataMap +
            '}';
    }
}
