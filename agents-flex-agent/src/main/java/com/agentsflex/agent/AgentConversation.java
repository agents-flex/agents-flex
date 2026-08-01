/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.core.memory.ChatMemory;
import com.agentsflex.core.memory.DefaultChatMemory;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.util.StringUtil;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 绑定 Agent 和 ChatMemory 的持续对话上下文。
 *
 * <p>AgentConversation 不重复维护消息列表，所有用户消息、模型消息和工具协议消息都保存在
 * {@link ChatMemory} 中。每次正常用户输入仍由 {@link AgentRunner} 创建新的 {@link AgentRun}，
 * Conversation 只负责让这些独立 Run 共享同一段对话历史。</p>
 *
 * <p>同一个 Conversation 同一时刻只允许存在一个未结束 Run。Run 阻塞时应通过
 * {@link AgentRunner#resume(AgentConversation, AgentResumeCommand)} 恢复，不能把审批决定或补充信息
 * 当成新的对话轮次。不同 Conversation 可以交给同一个 AgentRunner 独立执行；ChatModel、Tool 和
 * 自定义 ChatMemory 的线程安全仍由各自实现保证。</p>
 */
public final class AgentConversation {

    /** 写入 AgentRun metadata 的稳定会话标识键。 */
    public static final String RUN_METADATA_KEY = "agentsflex.conversation-id";

    /** 由业务系统持久化并用于关联多次 Run 的稳定会话标识。 */
    private final String id;
    /** 当前会话绑定的 Agent；会话中的每次新消息均使用该 Agent 创建 Run。 */
    private final Agent agent;
    /**
     * 跨多次 Run 共享的消息记忆；其持久化能力和保留策略由具体 ChatMemory 实现决定。
     */
    private final ChatMemory memory;
    /** 当前尚未结束的 Run ID；没有活动 Run 时为 null。 */
    private volatile String activeRunId;

    private AgentConversation(String id, Agent agent, ChatMemory memory) {
        if (!StringUtil.hasText(id)) {
            throw new IllegalArgumentException("conversation id must not be blank");
        }
        if (agent == null) {
            throw new IllegalArgumentException("agent must not be null");
        }
        if (memory == null) {
            throw new IllegalArgumentException("memory must not be null");
        }
        this.id = id;
        this.agent = agent;
        this.memory = memory;
    }

    /** 创建具有随机会话 ID 和进程内 Memory 的新对话。 */
    public static AgentConversation create(Agent agent) {
        return create(UUID.randomUUID().toString(), agent);
    }

    /** 创建具有指定会话 ID 和进程内 Memory 的新对话。 */
    public static AgentConversation create(String id, Agent agent) {
        return new AgentConversation(id, agent, new DefaultChatMemory(id));
    }

    /**
     * 使用应用提供的 ChatMemory 创建对话。
     *
     * <p>该入口适合接入自定义持久化 Memory，或使用已经加载了历史消息的 Memory 重建对话上下文。</p>
     */
    public static AgentConversation of(String id, Agent agent, ChatMemory memory) {
        return new AgentConversation(id, agent, memory);
    }

    /**
     * 使用已持久化的 Memory 和活动 Run ID 重建对话句柄。
     *
     * <p>框架不限定 Conversation 的业务表结构，应用可以从自己的会话记录加载 activeRunId。</p>
     */
    public static AgentConversation restore(String id, Agent agent, ChatMemory memory,
                                            String activeRunId) {
        AgentConversation conversation = new AgentConversation(id, agent, memory);
        if (!StringUtil.hasText(activeRunId)) {
            throw new IllegalArgumentException("activeRunId must not be blank");
        }
        conversation.activeRunId = activeRunId;
        return conversation;
    }

    /** @return 由上层会话存储使用的稳定会话 ID */
    public String getId() {
        return id;
    }

    /** @return 该持续对话绑定的完整 Agent 定义 */
    public Agent getAgent() {
        return agent;
    }

    /** @return 保存持续对话消息的 Memory 实例 */
    public ChatMemory getMemory() {
        return memory;
    }

    /** @return 当前未结束 Run 的 ID；没有活动 Run 时返回 null */
    public String getActiveRunId() {
        return activeRunId;
    }

    /** 返回对话消息的防御性副本。 */
    public synchronized List<Message> getMessages() {
        return Collections.unmodifiableList(AgentMessageUtils.copyMessages(
            memory.getMessages(Integer.MAX_VALUE)));
    }

    /** 在创建新 Run 前校验当前对话没有其他未结束执行。 */
    synchronized void assertCanStart() {
        if (StringUtil.hasText(activeRunId)) {
            throw new IllegalStateException("conversation already has an active run: " + activeRunId);
        }
    }

    /** 记录当前对话新创建的 Run。 */
    synchronized void activate(String runId) {
        assertCanStart();
        activeRunId = runId;
    }

    /** Run 到达终止状态后清除活动执行标识。 */
    synchronized void release(String runId) {
        if (runId != null && runId.equals(activeRunId)) {
            activeRunId = null;
        }
    }
}
