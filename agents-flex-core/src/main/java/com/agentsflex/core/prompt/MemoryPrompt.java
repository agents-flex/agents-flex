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
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

public class MemoryPrompt extends Prompt {

    private ChatMemory memory = new DefaultChatMemory();

    private SystemMessage systemMessage;

    private int maxAttachedMessageCount = 10;

    private boolean historyMessageTruncateEnable = false;
    private int historyMessageTruncateLength = 1000;
    private Function<String, String> historyMessageTruncateProcessor;

    // 临时消息不回存入 memory，只会当做 “过程消息” 参与大模型交互
    // 比如用于 Function call 等场景
    private List<Message> temporaryMessages;

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

    public MemoryPrompt() {
    }

    public MemoryPrompt(ChatMemory memory) {
        this.memory = memory;
    }

    public void addMessage(Message message) {
        memory.addMessage(message);
    }

    public void addMessageTemporary(Message message) {
        if (temporaryMessages == null) {
            temporaryMessages = new ArrayList<>();
        }
        temporaryMessages.add(message);
    }

    public void addMessages(Collection<Message> messages) {
        memory.addMessages(messages);
    }

    public ChatMemory getMemory() {
        return memory;
    }

    public void setMemory(ChatMemory memory) {
        this.memory = memory;
    }

    public List<Message> getTemporaryMessages() {
        return temporaryMessages;
    }

    public void setTemporaryMessages(List<Message> temporaryMessages) {
        this.temporaryMessages = temporaryMessages;
    }

    public void clearTemporaryMessages() {
        temporaryMessages.clear();
        temporaryMessages = null;
    }

    /**
     * 清空所有消息
     */
    public void clear() {
        memory.clear();
        if (temporaryMessages != null) {
            temporaryMessages.clear();
        }
    }

    @Override
    public List<Message> getMessages() {
        List<Message> messages = memory.getMessages(maxAttachedMessageCount);
        if (messages == null) {
            messages = new ArrayList<>();
        }

        if (historyMessageTruncateEnable) {
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                if (msg instanceof AbstractTextMessage) {
                    AbstractTextMessage<?> textMsg = (AbstractTextMessage<?>) msg;
                    String content = textMsg.getContent();
                    if (content == null) continue;

                    // 应用自定义处理器或默认截断
                    if (historyMessageTruncateProcessor != null) {
                        content = historyMessageTruncateProcessor.apply(content);
                    } else if (content.length() > historyMessageTruncateLength) {
                        content = content.substring(0, historyMessageTruncateLength);
                    }

                    // 创建新实例，避免修改原始消息
                    AbstractTextMessage<?> copied = textMsg.copy();
                    copied.setContent(content);
                    messages.set(i, copied);
                }
            }
        }

        // 插入系统消息
        if (systemMessage != null) {
            if (messages.isEmpty() || !(messages.get(0) instanceof SystemMessage)) {
                messages.add(0, systemMessage);
            }
        }

        //  添加临时消息（如果存在）
        if (temporaryMessages != null && !temporaryMessages.isEmpty()) {
            messages.addAll(new ArrayList<>(temporaryMessages));

            // 使用后自动清理
            temporaryMessages.clear();
            temporaryMessages = null;
        }

        return messages;
    }


}
