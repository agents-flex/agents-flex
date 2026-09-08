/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.model.chat.openai.responses;

import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatContextHolder;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.ChatRequestSpec;
import com.agentsflex.core.model.client.OpenAIChatClient;
import com.agentsflex.core.model.client.StreamClient;

/**
 * OpenAI Responses 协议客户端。
 * <p>
 * 复用 {@link OpenAIChatClient} 的同步 HTTP 传输和错误处理，
 * 使用独立解析器及流式监听器处理 Responses 响应。
 * 每次流式调用创建独立状态，客户端实例不保存请求级上下文。
 */
public class ResponsesChatClient extends OpenAIChatClient {

    /**
     * 创建绑定到指定模型的客户端。
     *
     * @param model 所属对话模型
     */
    public ResponsesChatClient(BaseChatModel<?> model) {
        super(model);
    }

    /**
     * 将同步请求的最终响应转换为框架消息或错误响应。
     *
     * @param raw     服务端原始 JSON
     * @param context 本次调用的上下文
     * @return 解析后的响应
     */
    @Override
    protected AiMessageResponse parseResponse(String raw, ChatContext context) {
        return new ResponsesResponseParser().parse(raw, context);
    }

    /**
     * 发起 SSE 请求，由请求独享的监听器管理增量消息和终止事件。
     * <p>
     * 不在此处重放已开始的流，以免重复发送业务回调。
     *
     * @param body     已由请求构建器生成的请求体
     * @param listener 业务响应监听器
     */
    @Override
    public void chatStream(String body, StreamResponseListener listener) {
        ChatContext context = ChatContextHolder.currentContext();
        ChatRequestSpec spec = context.getRequestSpec();
        StreamClient transport = getStreamClient();
        ResponsesStreamListener bridge = new ResponsesStreamListener(chatModel, context, transport, listener);
        try {
            // 先通知业务打开，允许业务在建立连接前取消请求。
            bridge.onStart(transport);
            if (bridge.isTerminal()) {
                return;
            }
            transport.start(spec.getUrl(), spec.getHeaders(), body, bridge, chatModel.getConfig());
        } catch (Exception error) {
            bridge.onFailure(transport, error);
        }
    }
}
