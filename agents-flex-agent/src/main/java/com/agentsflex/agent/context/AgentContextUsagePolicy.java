package com.agentsflex.agent.context;

/**
 * 基于供应商确认 Usage 的上下文占用与压缩触发策略。
 *
 * <p>该策略不会进行任何本地 Token 计算。只有模型响应返回完整 Usage 时才建立确认基准；
 * 达到阈值后仅记录“下次真实模型调用前需要压缩”，不会在当前响应处理中立即删除历史。</p>
 */
public final class AgentContextUsagePolicy {
    /**
     * 是否启用服务端 Usage 状态跟踪与 Token 超限恢复。
     */
    private final boolean enabled;
    /**
     * 按会话和模型作用域持久化 Usage 状态的 CAS Store。
     */
    private final AgentContextUsageStateStore stateStore;
    /**
     * 触发压缩的占用比例，取值范围为 (0, 1]。
     */
    private final double compressionThresholdRatio;
    /**
     * 为输出预留的 Token。大于 0 时优先级高于请求 maxTokens 和模型最大输出能力。
     */
    private final long reservedOutputTokens;
    /**
     * 输入或总上下文超限时是否允许压缩并重试。
     */
    private final boolean compressOnTokenLimitExceeded;
    /**
     * 单次真实模型调用链允许的上下文超限压缩重试次数。
     */
    private final int maxTokenLimitRecoveryAttempts;

    /**
     * 从构建器校验并冻结策略配置。
     */
    private AgentContextUsagePolicy(Builder builder) {
        if (builder.enabled && builder.stateStore == null) {
            throw new IllegalArgumentException("enabled context usage policy requires a stateStore");
        }
        if (!Double.isFinite(builder.compressionThresholdRatio)
            || builder.compressionThresholdRatio <= 0
            || builder.compressionThresholdRatio > 1) {
            throw new IllegalArgumentException("compressionThresholdRatio must be in (0, 1]");
        }
        if (builder.reservedOutputTokens < 0 || builder.maxTokenLimitRecoveryAttempts < 0) {
            throw new IllegalArgumentException("context usage limits must not be negative");
        }
        this.enabled = builder.enabled;
        this.stateStore = builder.stateStore;
        this.compressionThresholdRatio = builder.compressionThresholdRatio;
        this.reservedOutputTokens = builder.reservedOutputTokens;
        this.compressOnTokenLimitExceeded = builder.compressOnTokenLimitExceeded;
        this.maxTokenLimitRecoveryAttempts = builder.maxTokenLimitRecoveryAttempts;
    }

    /**
     * @return 默认关闭的策略；不会要求状态 Store
     */
    public static AgentContextUsagePolicy disabled() {
        return builder().build();
    }

    /**
     * @return 新的策略构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return 是否启用服务端 Usage 管理
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return Usage 状态 Store；策略关闭时可能为 {@code null}
     */
    public AgentContextUsageStateStore getStateStore() {
        return stateStore;
    }

    /**
     * @return 压缩阈值比例
     */
    public double getCompressionThresholdRatio() {
        return compressionThresholdRatio;
    }

    /**
     * @return 显式输出预留 Token；0 表示按请求或模型能力回退
     */
    public long getReservedOutputTokens() {
        return reservedOutputTokens;
    }

    /**
     * @return 是否在输入或总上下文超限时尝试压缩恢复
     */
    public boolean isCompressOnTokenLimitExceeded() {
        return compressOnTokenLimitExceeded;
    }

    /**
     * @return 允许的上下文超限恢复次数
     */
    public int getMaxTokenLimitRecoveryAttempts() {
        return maxTokenLimitRecoveryAttempts;
    }

    /**
     * Usage 策略构建器。
     */
    public static final class Builder {
        /**
         * 默认关闭，避免改变未配置该能力的现有 Agent 行为。
         */
        private boolean enabled;
        /**
         * 启用后必填的 CAS 状态 Store。
         */
        private AgentContextUsageStateStore stateStore;
        /**
         * 默认在可用输入容量达到 85% 时标记下次调用前压缩。
         */
        private double compressionThresholdRatio = 0.85d;
        /**
         * 默认不显式预留，交由请求 maxTokens 或模型能力决定。
         */
        private long reservedOutputTokens;
        /**
         * 默认允许上下文超限压缩恢复。
         */
        private boolean compressOnTokenLimitExceeded = true;
        /**
         * 默认最多恢复一次，防止持续压缩和无限重试。
         */
        private int maxTokenLimitRecoveryAttempts = 1;

        /**
         * 设置是否启用服务端 Usage 管理。
         */
        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        /**
         * 设置 Usage 状态 Store。
         */
        public Builder stateStore(AgentContextUsageStateStore value) {
            this.stateStore = value;
            return this;
        }

        /**
         * 设置压缩阈值比例，范围为 (0, 1]，且不得为 NaN 或无穷值。
         */
        public Builder compressionThresholdRatio(double value) {
            this.compressionThresholdRatio = value;
            return this;
        }

        /**
         * 设置显式输出预留 Token；0 表示使用回退优先级。
         */
        public Builder reservedOutputTokens(long value) {
            this.reservedOutputTokens = value;
            return this;
        }

        /**
         * 设置收到可恢复的 Token 上限异常后是否压缩并重试。
         */
        public Builder compressOnTokenLimitExceeded(boolean value) {
            this.compressOnTokenLimitExceeded = value;
            return this;
        }

        /**
         * 设置单次调用允许的 Token 上限恢复次数。
         */
        public Builder maxTokenLimitRecoveryAttempts(int value) {
            this.maxTokenLimitRecoveryAttempts = value;
            return this;
        }

        /**
         * 校验并创建不可变 Usage 策略。
         */
        public AgentContextUsagePolicy build() {
            return new AgentContextUsagePolicy(this);
        }
    }
}
