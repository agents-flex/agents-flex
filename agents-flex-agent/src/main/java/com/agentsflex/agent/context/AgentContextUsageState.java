package com.agentsflex.agent.context;

import java.io.Serializable;

/**
 * 单个“会话 + Agent 版本 + 模型”作用域的服务端 Usage 与压缩控制状态。
 *
 * <p>该对象是不可变值对象。每次确认 Usage、压缩或基准失效都会创建新实例并把版本加一，
 * 再通过 {@link AgentContextUsageStateStore} 的 CAS 语义保存。</p>
 *
 * <p>Token 字段只保存模型供应商返回的 Usage，不保存本地估算。压缩完成后历史数值仍被保留用于
 * 审计，但 {@link #status} 会变为 {@link AgentContextUsageStatus#BASELINE_INVALIDATED}，调用方不得
 * 再把这些数值当作当前上下文的有效占用。</p>
 */
public final class AgentContextUsageState implements Serializable {
    /**
     * Java 序列化版本号。
     */
    private static final long serialVersionUID = 1L;

    /**
     * CAS 版本；新作用域从 0 开始，成功保存的每个新状态必须严格加一。
     */
    private final long version;
    /**
     * 业务会话 ID。
     */
    private final String conversationId;
    /**
     * Agent 与模型组合形成的 Usage 作用域键。
     */
    private final String usageScopeKey;
    /**
     * 当前 Usage 基准的有效性状态。
     */
    private final AgentContextUsageStatus status;
    /**
     * 最近一次完整服务端 Usage 中的输入 Token。
     */
    private final long promptTokens;
    /**
     * 最近一次完整服务端 Usage 中的输出 Token。
     */
    private final long completionTokens;
    /**
     * 最近一次完整服务端 Usage 中的总 Token。
     */
    private final long totalTokens;
    /**
     * 下一次模型调用前用于展示占用比例和判断阈值的基准 Token。
     */
    private final long nextCallBaselineTokens;
    /**
     * 是否要求在下一次真实模型调用前执行压缩。
     */
    private final boolean compressionRequired;
    /**
     * 触发压缩或导致基准失效的原因。
     */
    private final AgentContextCompressionReason compressionReason;
    /**
     * 最近一次确认完整服务端 Usage 的时间戳，单位毫秒。
     */
    private final long confirmedAt;
    /**
     * 最近一次压缩导致基准失效的时间戳，单位毫秒。
     */
    private final long lastCompressionAt;

    /**
     * 完整构造 Usage 状态，主要供序列化恢复和状态转换方法使用。
     */
    public AgentContextUsageState(long version, String conversationId, String usageScopeKey,
                                  AgentContextUsageStatus status, long promptTokens,
                                  long completionTokens, long totalTokens,
                                  long nextCallBaselineTokens, boolean compressionRequired,
                                  AgentContextCompressionReason compressionReason,
                                  long confirmedAt, long lastCompressionAt) {
        if (version < 0 || promptTokens < 0 || completionTokens < 0 || totalTokens < 0
            || nextCallBaselineTokens < 0 || conversationId == null || usageScopeKey == null
            || status == null) {
            throw new IllegalArgumentException("invalid context usage state");
        }
        this.version = version;
        this.conversationId = conversationId;
        this.usageScopeKey = usageScopeKey;
        this.status = status;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.nextCallBaselineTokens = nextCallBaselineTokens;
        this.compressionRequired = compressionRequired;
        this.compressionReason = compressionReason;
        this.confirmedAt = confirmedAt;
        this.lastCompressionAt = lastCompressionAt;
    }

    /**
     * 创建尚未收到服务端 Usage 的初始状态。初始状态不会立即写入 Store，通常在第一次状态转换时保存。
     *
     * @param conversationId 业务会话 ID
     * @param usageScopeKey  Agent 与模型作用域键
     * @return 版本为 0 的初始状态
     */
    public static AgentContextUsageState empty(String conversationId, String usageScopeKey) {
        return new AgentContextUsageState(0, conversationId, usageScopeKey,
            AgentContextUsageStatus.NO_CONFIRMED_USAGE, 0, 0, 0, 0,
            false, null, 0, 0);
    }

    /**
     * @return CAS 版本
     */
    public long getVersion() {
        return version;
    }

    /**
     * @return 业务会话 ID
     */
    public String getConversationId() {
        return conversationId;
    }

    /**
     * @return Agent 与模型组合形成的 Usage 作用域键
     */
    public String getUsageScopeKey() {
        return usageScopeKey;
    }

    /**
     * @return Usage 基准状态
     */
    public AgentContextUsageStatus getStatus() {
        return status;
    }

    /**
     * @return 最近一次服务端确认的输入 Token
     */
    public long getPromptTokens() {
        return promptTokens;
    }

    /**
     * @return 最近一次服务端确认的输出 Token
     */
    public long getCompletionTokens() {
        return completionTokens;
    }

    /**
     * @return 最近一次服务端确认的总 Token
     */
    public long getTotalTokens() {
        return totalTokens;
    }

    /**
     * @return 下一次调用前的展示与阈值判断基准
     */
    public long getNextCallBaselineTokens() {
        return nextCallBaselineTokens;
    }

    /**
     * @return 是否需要在下一次真实模型调用前压缩
     */
    public boolean isCompressionRequired() {
        return compressionRequired;
    }

    /**
     * @return 压缩或失效原因；没有原因时为 {@code null}
     */
    public AgentContextCompressionReason getCompressionReason() {
        return compressionReason;
    }

    /**
     * @return 最近确认完整 Usage 的时间戳
     */
    public long getConfirmedAt() {
        return confirmedAt;
    }

    /**
     * @return 最近压缩时间戳；从未压缩时为 0
     */
    public long getLastCompressionAt() {
        return lastCompressionAt;
    }

    /**
     * 使用一份完整的供应商 Usage 建立或更新确认基准。
     *
     * <p>只有确实要求压缩时才保留 {@code reason}，避免普通确认状态携带误导性原因。</p>
     */
    public AgentContextUsageState confirmed(long prompt, long completion, long total,
                                            long baseline, boolean requireCompression,
                                            AgentContextCompressionReason reason, long now) {
        return new AgentContextUsageState(version + 1, conversationId, usageScopeKey,
            AgentContextUsageStatus.CONFIRMED_BASELINE, prompt, completion, total, baseline,
            requireCompression, requireCompression ? reason : null, now, lastCompressionAt);
    }

    /**
     * 因压缩或其他上下文变更使旧基准失效。历史 Token 数值保留用于审计，但压缩标记被消费。
     */
    public AgentContextUsageState invalidated(AgentContextCompressionReason reason, long now) {
        return new AgentContextUsageState(version + 1, conversationId, usageScopeKey,
            AgentContextUsageStatus.BASELINE_INVALIDATED, promptTokens, completionTokens,
            totalTokens, nextCallBaselineTokens, false, reason, confirmedAt, now);
    }

    /**
     * 后续模型调用成功但没有返回完整服务端 Usage 时，使此前确认的基准失效。
     *
     * <p>这不是压缩操作，因此不设置压缩原因，也不修改最近压缩时间。</p>
     */
    public AgentContextUsageState usageUnavailable() {
        return new AgentContextUsageState(version + 1, conversationId, usageScopeKey,
            AgentContextUsageStatus.BASELINE_INVALIDATED, promptTokens, completionTokens,
            totalTokens, nextCallBaselineTokens, false, null, confirmedAt, lastCompressionAt);
    }
}
