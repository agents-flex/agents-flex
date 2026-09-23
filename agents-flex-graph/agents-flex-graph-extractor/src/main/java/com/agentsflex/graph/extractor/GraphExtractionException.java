package com.agentsflex.graph.extractor;

/**
 * 模型调用、响应解析或抽取流水线无法继续时抛出的统一运行时异常。
 *
 * <p>当 {@code failOnChunkError=false} 时，流水线会把单 Chunk 的此类异常转换为
 * {@code CHUNK_EXTRACTION_FAILED}；其他入口和不可恢复的根协议错误会直接向调用方传播。</p>
 */
public class GraphExtractionException extends RuntimeException {
    /**
     * 保证异常跨版本序列化时使用稳定类型标识。
     */
    private static final long serialVersionUID = 1L;

    /**
     * @param message 面向开发者的失败原因
     */
    public GraphExtractionException(String message) {
        super(message);
    }

    /**
     * @param message 面向开发者的失败原因
     * @param cause   底层模型、协议或扩展实现异常
     */
    public GraphExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
