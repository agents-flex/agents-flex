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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 保存一个业务会话的完整消息时间线。
 *
 * <p>时间线既可以包含发送给模型的 UserMessage、AiMessage 和 ToolMessage，也可以包含
 * {@code modelVisible=false} 的页面状态消息。页面通常调用 {@link #getMessages(int)}，模型 Prompt
 * 调用 {@link #getModelMessages(int)}，从而让两类消息共用顺序和存储，又不会把审批按钮等 UI 数据
 * 发送给模型。</p>
 *
 * <p>需要与 AgentRunner 的可选会话投影集成时，持久化实现应覆盖窗口读取和按 ID 查询，并原子实现
 * {@link #addMessageIfAbsent(Message)} 和 {@link #updateMessage(Message, long)}。默认方法主要用于
 * 兼容已有的简单实现，不提供高性能分页或跨线程、跨进程原子保证。</p>
 */
public interface ChatMemory extends Memory {

    List<Message> getMessages(int count);

    /**
     * 从时间线末尾开始读取一个消息窗口，返回值仍按从旧到新的顺序排列。
     *
     * <p>{@code offset=0} 表示最新的消息窗口，{@code offset=20} 表示跳过最新 20 条后继续向前读取。
     * 数据库实现应覆盖本方法并使用分页或滑动窗口查询，避免将整个会话加载到内存。默认实现用于兼容
     * 已有 ChatMemory，会通过原 {@link #getMessages(int)} 读取覆盖当前窗口所需的尾部数据。</p>
     *
     * @param offset 从最新消息向前跳过的数量
     * @param count  最大返回数量
     */
    default List<Message> getMessages(int offset, int count) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be greater than 0");
        }
        long requested = (long) offset + count;
        if (requested >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "message window is too large; override paged getMessages in this ChatMemory");
        }
        List<Message> tail = getMessages((int) requested);
        if (tail == null || tail.isEmpty() || offset >= tail.size()) {
            return Collections.emptyList();
        }
        int end = tail.size() - offset;
        int start = Math.max(0, end - count);
        return new ArrayList<>(tail.subList(start, end));
    }

    /**
     * 返回可发送给模型的消息视图。
     *
     * <p>实现先从最新消息开始分批读取，再过滤 UI-only 消息，直到取得指定数量或到达时间线起点。
     * 因此页面消息不会挤占模型上下文窗口，也不需要先加载整个会话。</p>
     */
    default List<Message> getModelMessages(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be greater than 0");
        }
        int pageSize = count == Integer.MAX_VALUE ? 256 : Math.max(32, Math.min(256, count));
        List<Message> newestFirst = new ArrayList<>(Math.min(count, pageSize));
        int offset = 0;
        while (newestFirst.size() < count) {
            List<Message> page = getMessages(offset, pageSize);
            if (page == null || page.isEmpty()) break;
            for (int index = page.size() - 1;
                 index >= 0 && newestFirst.size() < count; index--) {
                Message message = page.get(index);
                if (message != null && message.isModelVisible()) {
                    newestFirst.add(message);
                }
            }
            if (page.size() < pageSize || offset > Integer.MAX_VALUE - page.size()) break;
            offset += page.size();
        }
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    void addMessage(Message message);

    /**
     * 按稳定消息 ID 查询一条消息。
     *
     * <p>持久化实现应覆盖为主键或索引查询。默认实现使用固定大小窗口逐页扫描，最多只在内存中保留
     * 一页消息。</p>
     */
    default Message getMessage(String messageId) {
        if (messageId == null || messageId.trim().isEmpty()) return null;
        int offset = 0;
        int pageSize = 128;
        while (true) {
            List<Message> page = getMessages(offset, pageSize);
            if (page == null || page.isEmpty()) return null;
            for (Message message : page) {
                if (message != null && Objects.equals(messageId, message.getMessageId())) {
                    return message;
                }
            }
            if (page.size() < pageSize || offset > Integer.MAX_VALUE - page.size()) return null;
            offset += page.size();
        }
    }

    /**
     * 按 messageId 幂等追加消息。
     *
     * @return 新增成功返回 true，消息已存在返回 false
     */
    default boolean addMessageIfAbsent(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        if (message.getMessageId() == null || message.getMessageId().trim().isEmpty()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        if (getMessage(message.getMessageId()) != null) return false;
        addMessage(message);
        return true;
    }

    /**
     * 使用乐观版本替换已有消息，只用于审批卡片等受控状态转换。
     *
     * <p>实现应使用 {@code messageId + expectedVersion} 作为更新条件，并在成功后把消息版本递增。
     * 找不到消息或版本不匹配时返回 false，不应覆盖较新的状态。</p>
     *
     * @return 更新成功返回 true，不存在或版本冲突返回 false
     * @throws UnsupportedOperationException 当前实现不支持消息更新时抛出
     */
    default boolean updateMessage(Message message, long expectedVersion) {
        throw new UnsupportedOperationException(
            "This ChatMemory does not support message updates");
    }

    default void addMessages(Collection<? extends Message> messages) {
        for (Message message : messages) {
            addMessage(message);
        }
    }

    /** 清空整个会话；AgentRunner 不会调用该管理操作。 */
    void clear();
}
