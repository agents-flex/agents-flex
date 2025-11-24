package com.agentsflex.core.model.chat;

import com.agentsflex.core.model.chat.response.AiMessageResponse;

/**
 * 聊天模型请求拦截器。
 * <p>
 * 通过责任链模式，在 LLM 调用前后插入自定义逻辑。
 * 支持同步（{@link #intercept}）和流式（{@link #interceptStream}）两种模式。
 */
public interface ChatInterceptor {

    /**
     * 拦截同步聊天请求。
     */
    AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context, SyncChain chain);

    /**
     * 拦截流式聊天请求。
     */
    void interceptStream(BaseChatModel<?> chatModel, ChatContext context, StreamResponseListener listener, StreamChain chain);
}
