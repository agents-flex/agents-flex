package com.agentsflex.core.model.chat;

import java.util.Map;

/**
 * 思考协议扩展点。实现必须无请求级可变状态，以便模型实例并发复用。
 */
public interface ThinkingProtocol {
    /**
     * enabled 为 null 时必须保留服务端默认行为，不添加覆盖参数。
     */
    void apply(Boolean enabled, Map<String, Object> body);

    /**
     * 是否需要在工具调用续接时回传公开的 reasoning_content。
     */
    default boolean preserveReasoningContent(Boolean enabled) {
        return false;
    }
}
