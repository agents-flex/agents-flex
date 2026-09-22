package com.agentsflex.graph;

/**
 * 后端不支持请求能力时抛出的异常，避免查询语义被静默改变。
 */
public class UnsupportedGraphFeatureException extends GraphException {
    /**
     * @param message 未支持能力及处理建议
     */
    public UnsupportedGraphFeatureException(String message) {
        super(message);
    }
}
