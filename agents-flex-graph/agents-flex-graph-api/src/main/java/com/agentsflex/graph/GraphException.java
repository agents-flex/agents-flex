package com.agentsflex.graph;

import com.agentsflex.graph.error.GraphErrorCode;

/**
 * Graph 模块所有运行时异常的基类。
 */
public class GraphException extends RuntimeException {
    /**
     * 稳定错误分类。
     */
    private final GraphErrorCode code;

    /**
     * @param message 面向调用方的错误说明
     */
    public GraphException(String message) {
        this(GraphErrorCode.UNKNOWN, message, null);
    }

    /**
     * @param message 错误说明 @param cause 底层异常
     */
    public GraphException(String message, Throwable cause) {
        this(GraphErrorCode.UNKNOWN, message, cause);
    }

    /**
     * 创建带稳定错误分类的异常。
     */
    public GraphException(GraphErrorCode code, String message) {
        this(code, message, null);
    }

    /**
     * 创建带稳定错误分类和底层原因的异常。
     */
    public GraphException(GraphErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? GraphErrorCode.UNKNOWN : code;
    }

    /**
     * @return 稳定错误分类；调用方不应依赖具体异常文本判断错误类型
     */
    public GraphErrorCode getCode() {
        return code;
    }
}
