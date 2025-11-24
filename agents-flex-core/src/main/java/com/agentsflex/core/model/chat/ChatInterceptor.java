/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
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
