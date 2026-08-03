/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.response.AiMessageResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单次 Agent step 的不可变结果。
 *
 * <p>调用方可以通过该对象判断本回合是执行了工具、产生最终答案，还是进入失败、取消等终止状态。
 * Agent 的累计状态仍以 {@link AgentRun} 为准。</p>
 */
public final class AgentStepResult {

    /**
     * 当前步骤的结果分类。
     */
    private final AgentStepType type;
    /**
     * 本步骤调用模型时得到的响应；未调用模型时可能为 null。
     */
    private final AiMessageResponse response;
    /**
     * 本步骤执行工具后生成的只读 ToolMessage 列表。
     */
    private final List<ToolMessage> toolMessages;
    /**
     * 本步骤失败或取消时关联的异常。
     */
    private final Throwable error;

    private AgentStepResult(AgentStepType type, AiMessageResponse response,
                            List<ToolMessage> toolMessages, Throwable error) {
        this.type = type;
        this.response = response;
        this.toolMessages = toolMessages == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(toolMessages));
        this.error = error;
    }

    /**
     * 仅供 AgentRunner 创建规范化步骤结果。
     */
    static AgentStepResult of(AgentStepType type, AiMessageResponse response,
                              List<ToolMessage> toolMessages, Throwable error) {
        return new AgentStepResult(type, response, toolMessages, error);
    }

    /**
     * @return 当前步骤的结果类型
     */
    public AgentStepType getType() {
        return type;
    }

    /**
     * @return 本步骤模型响应；没有模型响应时为 {@code null}
     */
    public AiMessageResponse getResponse() {
        return response;
    }

    /**
     * @return 不可修改的工具结果消息列表，没有工具结果时为空列表
     */
    public List<ToolMessage> getToolMessages() {
        return toolMessages;
    }

    /**
     * @return 本步骤关联异常；正常步骤通常为 {@code null}
     */
    public Throwable getError() {
        return error;
    }

}
