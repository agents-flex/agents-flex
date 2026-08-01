/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.core.agent.tool;

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

    public enum Outcome { ALLOW, REQUIRE_APPROVAL, DENY }

    public static final ToolApprovalDecision ALLOW = builder(Outcome.ALLOW).build();
    public static final ToolApprovalDecision REQUIRE_APPROVAL = builder(Outcome.REQUIRE_APPROVAL).build();
    public static final ToolApprovalDecision DENY = builder(Outcome.DENY).build();

    private final Outcome outcome;
    private final String code;
    private final String message;
    private final String reason;
    private final Map<String, Object> metadata;

    private ToolApprovalDecision(Builder builder) {
        this.outcome = builder.outcome;
        this.code = builder.code;
        this.message = builder.message;
        this.reason = builder.reason;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
    }

    public static Builder builder(Outcome outcome) { return new Builder(outcome); }
    public static Builder allow() { return builder(Outcome.ALLOW); }
    public static Builder requireApproval() { return builder(Outcome.REQUIRE_APPROVAL); }
    public static Builder deny() { return builder(Outcome.DENY); }

    public Outcome getOutcome() { return outcome; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getReason() { return reason; }
    public Map<String, Object> getMetadata() { return metadata; }

    public static final class Builder {
        private final Outcome outcome;
        private String code;
        private String message;
        private String reason;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(Outcome outcome) {
            if (outcome == null) throw new IllegalArgumentException("outcome must not be null");
            this.outcome = outcome;
        }

        public Builder code(String value) { code = value; return this; }
        public Builder message(String value) { message = value; return this; }
        public Builder reason(String value) { reason = value; return this; }
        public Builder metadata(String key, Object value) {
            if (key == null) throw new IllegalArgumentException("metadata key must not be null");
            metadata.put(key, value);
            return this;
        }
        public ToolApprovalDecision build() { return new ToolApprovalDecision(this); }
    }
}
