package com.agentsflex.graph.extractor.model;

/**
 * 抽取、解析、校验或映射阶段产生的不可变结构化问题。
 *
 * <p>稳定问题码适合程序分流和质量统计，candidateKey 用于定位具体 Chunk 或候选项，message
 * 用于开发者诊断。调用方不应依赖英文 message 做业务判断。</p>
 */
public final class GraphExtractionIssue {
    /**
     * 问题严重级别。
     */
    public enum Severity {WARNING, ERROR}

    /**
     * 稳定问题码。
     */
    private final String code;
    /**
     * 问题严重级别。
     */
    private final Severity severity;
    /**
     * 涉及的候选键。
     */
    private final String candidateKey;
    /**
     * 面向开发者和审核者的问题描述。
     */
    private final String message;

    /**
     * 创建结构化问题。
     *
     * @param code         供程序判断的稳定非空问题码
     * @param severity     警告或错误级别
     * @param candidateKey 相关候选键或 Chunk 定位；未知时可以为空
     * @param message      面向开发者的诊断说明
     */
    public GraphExtractionIssue(String code, Severity severity, String candidateKey, String message) {
        if (code == null || code.trim().isEmpty()) throw new IllegalArgumentException("issue code must not be blank");
        if (severity == null) throw new IllegalArgumentException("severity must not be null");
        this.code = code;
        this.severity = severity;
        this.candidateKey = candidateKey == null ? "" : candidateKey;
        this.message = message == null ? "" : message;
    }

    /**
     * @return 稳定问题码。
     */
    public String getCode() {
        return code;
    }

    /**
     * @return 严重级别。
     */
    public Severity getSeverity() {
        return severity;
    }

    /**
     * @return 涉及的候选键。
     */
    public String getCandidateKey() {
        return candidateKey;
    }

    /**
     * @return 问题描述。
     */
    public String getMessage() {
        return message;
    }
}
