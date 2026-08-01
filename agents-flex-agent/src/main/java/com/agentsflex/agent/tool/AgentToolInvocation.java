/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.tool;

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

    /** 直接执行当前 ToolCall 的 Run ID。 */
    private final String runId;
    /** 父子运行树最顶层的 Run ID。 */
    private final String rootRunId;
    /** 父 Run ID；根运行中的工具调用为空。 */
    private final String parentRunId;
    /** 工具所属 Agent 的稳定 ID。 */
    private final String agentId;
    /** 工具所属 Agent 的配置版本。 */
    private final String agentVersion;
    /** 模型生成或 Runner 补全的稳定 ToolCall ID。 */
    private final String toolCallId;
    /** Agent 内唯一的工具名称。 */
    private final String toolName;

    public AgentToolInvocation(String runId, String rootRunId, String parentRunId,
                               String agentId, String agentVersion, String toolCallId,
                               String toolName) {
        if (!StringUtil.hasText(runId) || !StringUtil.hasText(agentId)
            || !StringUtil.hasText(agentVersion) || !StringUtil.hasText(toolCallId)
            || !StringUtil.hasText(toolName)) {
            throw new IllegalArgumentException(
                "runId, agentId, agentVersion, toolCallId and toolName must not be blank");
        }
        this.runId = runId;
        this.rootRunId = rootRunId;
        this.parentRunId = parentRunId;
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
    }

    /** 返回当前线程正在执行的 Agent Tool 调用；非 AgentRunner 调用时返回 {@code null}。 */
    public static AgentToolInvocation current() {
        ToolContext context = ToolContextHolder.currentContext();
        return context == null ? null : context.getAttribute(CONTEXT_ATTRIBUTE);
    }

    /** @return 直接执行工具的 Run ID */
    public String getRunId() { return runId; }
    /** @return 父子运行树的根 Run ID */
    public String getRootRunId() { return rootRunId; }
    /** @return 父 Run ID；根运行返回 {@code null} */
    public String getParentRunId() { return parentRunId; }
    /** @return 工具所属 Agent ID */
    public String getAgentId() { return agentId; }
    /** @return 工具所属 Agent 配置版本 */
    public String getAgentVersion() { return agentVersion; }
    /** @return 当前 ToolCall 的稳定 ID */
    public String getToolCallId() { return toolCallId; }
    /** @return 当前工具名称 */
    public String getToolName() { return toolName; }

    /** 返回跨进程恢复后仍保持稳定的默认幂等键。 */
    public String getIdempotencyKey() {
        return runId + ":" + toolCallId;
    }
}
