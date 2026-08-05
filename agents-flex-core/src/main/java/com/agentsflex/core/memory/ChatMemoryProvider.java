/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.memory;

/**
 * 根据业务会话 ID 定位 {@link ChatMemory} 的提供者。
 *
 * <p>该接口只负责把稳定的 {@code conversationId} 映射到业务系统管理的 ChatMemory，不负责创建
 * conversationId，也不拥有会话或消息的生命周期。数据库实现通常在这里返回绑定到指定会话记录的
 * ChatMemory；单实例应用也可以返回进程内实现。</p>
 *
 * <p>同一个 conversationId 应始终定位到同一条逻辑会话。找不到会话时可以返回 {@code null}，调用方
 * 应将其视为会话不存在，而不是静默创建另一条会话。</p>
 */
@FunctionalInterface
public interface ChatMemoryProvider {

    /**
     * 获取指定业务会话的 ChatMemory。
     *
     * @param conversationId 稳定且非空的业务会话 ID
     * @return 对应的 ChatMemory；会话不存在时返回 {@code null}
     */
    ChatMemory getMemory(String conversationId);
}
