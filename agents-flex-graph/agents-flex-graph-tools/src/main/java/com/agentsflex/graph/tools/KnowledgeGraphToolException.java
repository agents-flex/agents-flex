package com.agentsflex.graph.tools;

/**
 * 可预期、可由模型自行纠正的知识图谱 Tool 异常。
 *
 * <p>该异常只在模块内部传播，Tool 边界会把它转换为 {@code Error: CODE: message}，避免把
 * Java 堆栈或后端实现细节直接暴露给模型。</p>
 */
final class KnowledgeGraphToolException extends RuntimeException {
    /**
     * 稳定的机器可读错误码。
     */
    private final String code;

    /**
     * @param code    稳定错误码
     * @param message 面向模型的可纠正错误说明
     */
    KnowledgeGraphToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * @return 稳定错误码
     */
    String getCode() {
        return code;
    }
}
