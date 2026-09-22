package com.agentsflex.graph;

/**
 * Graph 模块所有运行时异常的基类。
 */
public class GraphException extends RuntimeException {
    /**
     * @param message 面向调用方的错误说明
     */
    public GraphException(String message) {
        super(message);
    }

    /**
     * @param message 错误说明 @param cause 底层异常
     */
    public GraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
