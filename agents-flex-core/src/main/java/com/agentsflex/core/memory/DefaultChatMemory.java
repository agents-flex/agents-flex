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
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 基于进程内 List 的 ChatMemory，适合测试和单实例临时会话。
 *
 * <p>全部读写在实例内同步，支持稳定消息 ID 的幂等追加和基于版本的 CAS 更新；进程退出后内容丢失，
 * 生产环境应替换为数据库或缓存实现。</p>
 */
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
    public synchronized List<Message> getMessages(int count) {
        return getMessages(0, count);
    }

    @Override
    public synchronized List<Message> getMessages(int offset, int count) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be greater than 0");
        }
        if (offset >= messages.size()) return Collections.emptyList();
        int end = messages.size() - offset;
        int start = Math.max(0, end - count);
        return new ArrayList<>(messages.subList(start, end));
    }

    @Override
    public synchronized Message getMessage(String messageId) {
        if (messageId == null || messageId.trim().isEmpty()) return null;
        for (Message message : messages) {
            if (message != null && messageId.equals(message.getMessageId())) return message;
        }
        return null;
    }

    @Override
    public synchronized void addMessage(Message message) {
        messages.add(message);
    }

    @Override
    public synchronized boolean addMessageIfAbsent(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        String messageId = message.getMessageId();
        if (messageId == null || messageId.trim().isEmpty()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        if (getMessage(messageId) != null) return false;
        messages.add(message);
        return true;
    }

    @Override
    public synchronized boolean updateMessage(Message message, long expectedVersion) {
        if (message == null || message.getMessageId() == null) return false;
        for (int index = 0; index < messages.size(); index++) {
            Message existing = messages.get(index);
            if (message.getMessageId().equals(existing.getMessageId())
                && existing.getVersion() == expectedVersion) {
                message.setVersion(expectedVersion + 1);
                messages.set(index, message);
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized void clear() {
        messages.clear();
    }
}
