/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

/**
 * 同一业务会话已经存在未结束 AgentTurn 时，拒绝创建新的普通 Turn。
 *
 * <p>这是会话级并发冲突，不表示现有 Turn 执行失败。调用方可以根据状态返回 HTTP 409、
 * 把消息放入业务队列，或提示用户先完成表单/审批。</p>
 */
public final class AgentConversationBusyException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final String conversationId;
    private final String activeTurnId;
    private final AgentTurnStatus status;

    public AgentConversationBusyException(String conversationId, String activeTurnId,
                                          AgentTurnStatus status) {
        super("conversation has an active AgentTurn: conversationId=" + conversationId
            + ", turnId=" + activeTurnId + ", status=" + status);
        this.conversationId = conversationId;
        this.activeTurnId = activeTurnId;
        this.status = status;
    }

    public String getConversationId() { return conversationId; }
    public String getActiveTurnId() { return activeTurnId; }
    public AgentTurnStatus getStatus() { return status; }
}
