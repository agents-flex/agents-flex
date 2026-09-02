/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.tool;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具本次执行对应的恢复来源、恢复次数、重试信息和可持久化审计信息。
 *
 * <p>框架定义的重试字段使用强类型属性保存；{@code metadata} 仅用于审批、表单以及业务侧自定义
 * 的扩展数据，避免调用方通过字符串键读取核心运行状态。</p>
 *
 * <p>新增字段均使用基础类型并以 {@code 0} 表示未设置，因此旧版本 Snapshot 缺少这些字段时仍可
 * 正常读取；业务若需要区分“未设置”和真实时间戳，应先检查 {@link #getType()}。</p>
 */
public final class AgentToolResumeInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 最近一次恢复的来源；NONE 表示该 ToolCall 尚未恢复。
     */
    private final AgentToolResumeType type;
    /**
     * 当前 ToolCall 累计发生的恢复次数，不等同于工具函数执行次数。
     */
    private final int resumeCount;
    /**
     * 当前 ToolCall 的错误重试序号；非 RETRY 恢复时为 0。
     */
    private final int retryAttempt;
    /**
     * 当前错误重试计划的下一次执行时间；无重试计划时为 0。
     */
    private final long retryNextRunnableAt;
    /**
     * 审批、表单和业务侧扩展数据，不承载框架核心重试状态。
     */
    private final Map<String, Object> metadata;
    /**
     * 最近一次恢复关联的异常类型名称；没有关联异常时为 null。
     */
    private final String previousErrorType;
    /**
     * 最近一次恢复关联的异常消息；没有关联异常时为 null。
     */
    private final String previousErrorMessage;

    /**
     * 创建工具恢复信息。
     *
     * @param type                 最近一次恢复来源
     * @param resumeCount          当前 ToolCall 累计恢复次数，包含审批、表单和错误重试
     * @param retryAttempt         错误重试序号；非 {@link AgentToolResumeType#RETRY} 时会归零
     * @param retryNextRunnableAt  错误重试计划执行时间；无重试计划时会归零
     * @param metadata             审批、表单或业务侧自定义扩展数据
     * @param previousErrorType    上一次错误的 Java 类型名称
     * @param previousErrorMessage 上一次错误消息；是否脱敏由业务侧的异常处理策略决定
     */
    public AgentToolResumeInfo(AgentToolResumeType type, int resumeCount,
                               int retryAttempt, long retryNextRunnableAt,
                               Map<String, ?> metadata, String previousErrorType,
                               String previousErrorMessage) {
        this.type = type == null ? AgentToolResumeType.NONE : type;
        this.resumeCount = Math.max(0, resumeCount);
        this.retryAttempt = this.type == AgentToolResumeType.RETRY
            ? Math.max(0, retryAttempt) : 0;
        this.retryNextRunnableAt = this.type == AgentToolResumeType.RETRY
            ? Math.max(0L, retryNextRunnableAt) : 0L;
        this.metadata = metadata == null || metadata.isEmpty()
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
        this.previousErrorType = previousErrorType;
        this.previousErrorMessage = previousErrorMessage;
    }

    public static AgentToolResumeInfo none() {
        return new AgentToolResumeInfo(AgentToolResumeType.NONE, 0, 0, 0L,
            null, null, null);
    }

    public AgentToolResumeType getType() {
        return type;
    }

    /**
     * 返回该 ToolCall 在此前被 Runner 恢复的累计次数。
     *
     * <p>该计数描述“恢复动作”而不是工具函数执行次数：审批通过、表单再次提交和错误重试
     * 都会使它加一。工具函数实际执行了几次应使用 {@link AgentToolContext#getExecutionAttempt()}。</p>
     *
     * @return 非负的累计恢复次数
     */
    public int getResumeCount() {
        return resumeCount;
    }

    /**
     * @return 当前 ToolCall 的错误重试序号；不是错误重试恢复时返回 0
     */
    public int getRetryAttempt() {
        return retryAttempt;
    }

    /**
     * @return 当前错误重试计划的下一次执行时间；没有重试计划时返回 0
     */
    public long getRetryNextRunnableAt() {
        return retryNextRunnableAt;
    }

    /**
     * 返回恢复时附带的扩展数据。
     *
     * <p>返回值是只读 Map 的快照。框架核心状态（例如重试序号和调度时间）不放在这里；业务侧
     * 可以安全地使用该 Map 保存审批人、表单来源、业务错误码或供应商请求 ID 等扩展字段。</p>
     *
     * @return 不可修改且不会受构造参数后续修改影响的扩展数据
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * @return 上一次恢复关联的错误 Java 类型名称；没有错误时为 {@code null}
     */
    public String getPreviousErrorType() {
        return previousErrorType;
    }

    /**
     * @return 上一次恢复关联的错误消息；没有错误时为 {@code null}
     */
    public String getPreviousErrorMessage() {
        return previousErrorMessage;
    }

    /**
     * @return 类型是否不是 {@link AgentToolResumeType#NONE}，即本次执行是否由恢复触发
     */
    public boolean isResumed() {
        return type != AgentToolResumeType.NONE;
    }
}
