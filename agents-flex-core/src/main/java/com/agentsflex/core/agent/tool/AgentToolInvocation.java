/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.tool;

import com.agentsflex.core.model.chat.tool.ToolContext;
import com.agentsflex.core.model.chat.tool.ToolContextHolder;
import com.agentsflex.core.util.StringUtil;

import java.io.Serializable;

/**
 * Tool 执行期间可读取的 AgentRun 调用身份。
 *
 * <p>该对象把 Run、Agent 和 ToolCall 的稳定标识传递给 ToolInterceptor 与 Tool 实现。
 * 副作用工具可以使用 {@link #getIdempotencyKey()} 作为外部请求幂等键，审计组件也可以据此
 * 关联根任务、子任务和具体工具调用。</p>
 */
public final class AgentToolInvocation implements Serializable {

    private static final long serialVersionUID = 1L;

    /** AgentRunner 写入 ToolContext 的属性键。 */
    public static final String CONTEXT_ATTRIBUTE = AgentToolInvocation.class.getName();

    private final String runId;
    private final String rootRunId;
    private final String parentRunId;
    private final String agentId;
    private final String agentVersion;
    private final String toolCallId;
    private final AgentToolReference toolReference;

    public AgentToolInvocation(String runId, String rootRunId, String parentRunId,
                               String agentId, String agentVersion, String toolCallId,
                               AgentToolReference toolReference) {
        if (!StringUtil.hasText(runId) || !StringUtil.hasText(agentId)
            || !StringUtil.hasText(agentVersion) || !StringUtil.hasText(toolCallId)
            || toolReference == null) {
            throw new IllegalArgumentException(
                "runId, agentId, agentVersion and toolCallId must not be blank; "
                    + "toolReference must not be null");
        }
        if (!agentId.equals(toolReference.getAgentId())
            || !agentVersion.equals(toolReference.getAgentVersion())) {
            throw new IllegalArgumentException("toolReference does not belong to the Agent version");
        }
        this.runId = runId;
        this.rootRunId = rootRunId;
        this.parentRunId = parentRunId;
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.toolCallId = toolCallId;
        this.toolReference = toolReference;
    }

    /** 返回当前线程正在执行的 Agent Tool 调用；非 AgentRunner 调用时返回 {@code null}。 */
    public static AgentToolInvocation current() {
        ToolContext context = ToolContextHolder.currentContext();
        return context == null ? null : context.getAttribute(CONTEXT_ATTRIBUTE);
    }

    public String getRunId() { return runId; }
    public String getRootRunId() { return rootRunId; }
    public String getParentRunId() { return parentRunId; }
    public String getAgentId() { return agentId; }
    public String getAgentVersion() { return agentVersion; }
    public String getToolCallId() { return toolCallId; }
    public AgentToolReference getToolReference() { return toolReference; }

    /** 返回跨进程恢复后仍保持稳定的默认幂等键。 */
    public String getIdempotencyKey() {
        return runId + ":" + toolCallId;
    }
}
