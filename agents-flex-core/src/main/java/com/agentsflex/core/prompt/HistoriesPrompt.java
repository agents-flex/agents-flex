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

import com.agentsflex.core.memory.ChatMemory;
import com.agentsflex.core.memory.DefaultChatMemory;
import com.agentsflex.core.message.AbstractTextMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class HistoriesPrompt extends Prompt {

    private ChatMemory memory = new DefaultChatMemory();

    private SystemMessage systemMessage;

    private int maxAttachedMessageCount = 10;

    private boolean historyMessageTruncateEnable = false;
    private int historyMessageTruncateLength = 1000;
    private Function<String, String> historyMessageTruncateProcessor;

    public SystemMessage getSystemMessage() {
        return systemMessage;
    }

    public void setSystemMessage(SystemMessage systemMessage) {
        this.systemMessage = systemMessage;
    }

    public int getMaxAttachedMessageCount() {
        return maxAttachedMessageCount;
    }

    public void setMaxAttachedMessageCount(int maxAttachedMessageCount) {
        this.maxAttachedMessageCount = maxAttachedMessageCount;
    }

    public boolean isHistoryMessageTruncateEnable() {
        return historyMessageTruncateEnable;
    }

    public void setHistoryMessageTruncateEnable(boolean historyMessageTruncateEnable) {
        this.historyMessageTruncateEnable = historyMessageTruncateEnable;
    }

    public int getHistoryMessageTruncateLength() {
        return historyMessageTruncateLength;
    }

    public void setHistoryMessageTruncateLength(int historyMessageTruncateLength) {
        this.historyMessageTruncateLength = historyMessageTruncateLength;
    }

    public Function<String, String> getHistoryMessageTruncateProcessor() {
        return historyMessageTruncateProcessor;
    }

    public void setHistoryMessageTruncateProcessor(Function<String, String> historyMessageTruncateProcessor) {
        this.historyMessageTruncateProcessor = historyMessageTruncateProcessor;
    }

    public HistoriesPrompt() {
    }

    public HistoriesPrompt(ChatMemory memory) {
        this.memory = memory;
    }

    public void addMessage(Message message) {
        memory.addMessage(message);
    }

    public ChatMemory getMemory() {
        return memory;
    }

    public void setMemory(ChatMemory memory) {
        this.memory = memory;
    }

    @Override
    public List<Message> toMessages() {
        List<Message> messages = memory.getMessages();
        if (messages == null) messages = new ArrayList<>();

        if (messages.size() > maxAttachedMessageCount) {
            messages = messages.subList(messages.size() - maxAttachedMessageCount, messages.size());
        }

        if (historyMessageTruncateEnable) {
            for (Message message : messages) {
                if (message instanceof AbstractTextMessage) {
                    String content = ((AbstractTextMessage) message).getContent();
                    if (historyMessageTruncateProcessor != null) {
                        content = historyMessageTruncateProcessor.apply(content);
                    } else if (content.length() > historyMessageTruncateLength) {
                        content = content.substring(0, historyMessageTruncateLength);
                    }
                    ((AbstractTextMessage) message).setContent(content);
                }
            }
        }

        if (systemMessage != null) {
            messages.add(0, systemMessage);
        }

        return messages;
    }
}
