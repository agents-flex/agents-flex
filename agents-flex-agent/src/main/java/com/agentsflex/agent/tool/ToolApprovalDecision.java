/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.tool;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具审批策略返回的结构化决策。
 *
 * <p>除允许、等待审批和拒绝三种结果外，还可携带策略代码、面向使用者的说明、审计原因及
 * 可序列化元数据。等待审批时这些信息会保存到 AgentSuspension。</p>
 */
public final class ToolApprovalDecision implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 工具审批策略可以返回的处理结果。
     */
    public enum Outcome {
        /**
         * 立即执行工具，不进入等待状态。
         */
        ALLOW,
        /**
         * 保存暂停信息，等待外部批准或拒绝。
         */
        REQUIRE_APPROVAL,
        /**
         * 不执行工具，并把结构化拒绝结果返回给模型。
         */
        DENY
    }

    /**
     * 不携带附加信息的共享允许决定。
     */
    public static final ToolApprovalDecision ALLOW = builder(Outcome.ALLOW).build();
    /**
     * 不携带附加信息的共享等待审批决定。
     */
    public static final ToolApprovalDecision REQUIRE_APPROVAL = builder(Outcome.REQUIRE_APPROVAL).build();
    /**
     * 不携带附加信息的共享拒绝决定。
     */
    public static final ToolApprovalDecision DENY = builder(Outcome.DENY).build();

    /**
     * 审批处理结果。
     */
    private final Outcome outcome;
    /**
     * 供程序判断策略分支的稳定业务代码。
     */
    private final String code;
    /**
     * 适合展示给最终使用者的说明。
     */
    private final String message;
    /**
     * 适合审计和问题定位的内部原因。
     */
    private final String reason;
    /**
     * 随暂停点或拒绝结果保存的只读扩展信息。
     */
    private final Map<String, Object> metadata;

    /**
     * 从构建器冻结审批结果及扩展元数据。
     */
    private ToolApprovalDecision(Builder builder) {
        this.outcome = builder.outcome;
        this.code = builder.code;
        this.message = builder.message;
        this.reason = builder.reason;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
    }

    /**
     * 创建指定处理结果的决策构建器。
     */
    public static Builder builder(Outcome outcome) {
        return new Builder(outcome);
    }

    /**
     * 创建允许执行的决策构建器。
     */
    public static Builder allow() {
        return builder(Outcome.ALLOW);
    }

    /**
     * 创建等待外部审批的决策构建器。
     */
    public static Builder requireApproval() {
        return builder(Outcome.REQUIRE_APPROVAL);
    }

    /**
     * 创建拒绝执行的决策构建器。
     */
    public static Builder deny() {
        return builder(Outcome.DENY);
    }

    /**
     * @return 审批处理结果
     */
    public Outcome getOutcome() {
        return outcome;
    }

    /**
     * @return 稳定业务代码
     */
    public String getCode() {
        return code;
    }

    /**
     * @return 面向使用者的说明
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return 审计原因
     */
    public String getReason() {
        return reason;
    }

    /**
     * @return 不可修改的扩展元数据
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * 结构化工具审批决定构建器。
     */
    public static final class Builder {
        private final Outcome outcome;
        private String code;
        private String message;
        private String reason;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        /**
         * @param outcome 必需的审批处理结果
         */
        private Builder(Outcome outcome) {
            if (outcome == null) throw new IllegalArgumentException("outcome must not be null");
            this.outcome = outcome;
        }

        /**
         * 设置稳定业务代码。
         */
        public Builder code(String value) {
            code = value;
            return this;
        }

        /**
         * 设置面向使用者的说明。
         */
        public Builder message(String value) {
            message = value;
            return this;
        }

        /**
         * 设置审计原因。
         */
        public Builder reason(String value) {
            reason = value;
            return this;
        }

        /**
         * 添加一项随决策保存的元数据。
         */
        public Builder metadata(String key, Object value) {
            if (key == null) throw new IllegalArgumentException("metadata key must not be null");
            metadata.put(key, value);
            return this;
        }

        /**
         * 创建不可变审批决定。
         */
        public ToolApprovalDecision build() {
            return new ToolApprovalDecision(this);
        }
    }
}
