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

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.BaseChatConfig;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatContextHolder;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamClient;
import com.agentsflex.core.model.client.StreamClientListener;
import com.agentsflex.core.model.client.StreamContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.util.Map;

/**
 * 将 Responses SSE 事件转换为框架的流式回调。
 * <p>
 * 每个请求独立创建监听器，通过同步方法串行处理事件和业务取消。
 * 正文和推理摘要按增量发送；工具调用以终止响应中的完整快照为准，
 * 避免交错参数分片被错误合并或提前执行。
 */
class ResponsesStreamListener implements StreamClientListener {

    private final ChatContext chatContext;
    private final StreamClient transport;
    private final StreamResponseListener listener;
    private final StreamContext context;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    /** 是否已通知业务打开，用于去重客户端和底层传输的启动通知。 */
    private boolean opened;
    /** 已收到终止响应、发生错误或主动取消；后续事件不再发送业务消息。 */
    private boolean terminal;
    /** 是否已发送 onClose，保证关闭回调只执行一次。 */
    private boolean closed;

    /**
     * 创建请求独享的事件桥接器，并对外暴露可区分主动取消的 StreamClient。
     *
     * @param model       所属对话模型
     * @param chatContext 本次请求上下文
     * @param transport   实际 SSE 传输
     * @param listener    业务响应监听器
     */
    ResponsesStreamListener(
        ChatModel model, ChatContext chatContext, StreamClient transport, StreamResponseListener listener) {
        this.chatContext = chatContext;
        this.transport = transport;
        this.listener = listener;
        this.context = new StreamContext(model, chatContext, new StreamClient() {
            @Override
            public void start(String url, Map<String, String> headers, String payload, StreamClientListener target,
                BaseChatConfig config) {
                throw new UnsupportedOperationException("Stream already started");
            }

            @Override
            public void stop() {
                cancel();
            }
        });
    }

    @Override
    public synchronized void onStart(StreamClient client) {
        if (opened || terminal) {
            return;
        }
        opened = true;
        inContext(() -> listener.onOpen(context));
    }

    @Override
    public synchronized void onMessage(StreamClient client, String raw) {
        if (terminal) {
            return;
        }
        inContext(() -> {
            try {
                JSONObject event = JSON.parseObject(raw);
                String type = event.getString("type");
                if ("response.completed".equals(type) || "response.incomplete".equals(type)
                    || "response.failed".equals(type)) {
                    AiMessageResponse response =
                        new ResponsesResponseParser().parse(event.getString("response"), chatContext);
                    if (response.isError()) {
                        fail(response.toException());
                        return;
                    }
                    AiMessage full = response.getMessage();
                    full.setFullContent(full.getContent());
                    full.setFullReasoningContent(full.getReasoningContent());
                    // 最终消息只携带 fullContent，避免调用方再次追加已经收到的正文。
                    full.setContent("");
                    full.setReasoningContent(null);
                    context.setFullMessage(full);
                    terminal = true;
                    try {
                        listener.onMessage(context, response);
                    } finally {
                        try {
                            close();
                        } finally {
                            transport.stop();
                        }
                    }
                } else if ("error".equals(type)) {
                    AiMessageResponse error = AiMessageResponse.error(chatContext, raw, event.getString("message"));
                    error.setErrorCode(event.getString("code"));
                    fail(error.toException());
                } else if ("response.output_text.delta".equals(type)
                    || "response.reasoning_summary_text.delta".equals(type)) {
                    AiMessage delta = new AiMessage();
                    String value = event.getString("delta");
                    if (value == null) {
                        return;
                    }
                    if ("response.output_text.delta".equals(type)) {
                        content.append(value);
                        delta.setContent(value);
                    } else {
                        reasoning.append(value);
                        delta.setReasoningContent(value);
                    }
                    delta.setFullContent(content.toString());
                    delta.setFullReasoningContent(reasoning.toString());
                    listener.onMessage(context, new AiMessageResponse(chatContext, raw, delta));
                }
            } catch (Exception error) {
                fail(error);
            }
        });
    }

    @Override
    public synchronized void onStop(StreamClient client) {
        inContext(() -> {
            // 底层连接关闭不等于模型正常完成，必须先收到协议终止事件。
            if (!terminal) {
                fail(new IOException("Responses stream ended before a terminal event"));
            } else {
                close();
            }
        });
    }

    @Override
    public synchronized void onFailure(StreamClient client, Throwable error) {
        inContext(() -> fail(error));
    }

    /** @return 当前请求是否已完成、失败或被取消 */
    synchronized boolean isTerminal() {
        return terminal;
    }

    /**
     * 处理业务主动取消，只关闭连接，不伪造成功消息或断流错误。
     */
    private synchronized void cancel() {
        inContext(() -> {
            if (terminal) {
                return;
            }
            terminal = true;
            try {
                close();
            } finally {
                transport.stop();
            }
        });
    }

    /**
     * 记录首次错误，并保证错误回调后关闭业务生命周期和底层连接。
     */
    private void fail(Throwable error) {
        if (terminal) {
            return;
        }
        terminal = true;
        context.setThrowable(error);
        try {
            listener.onError(context, error);
        } finally {
            try {
                close();
            } finally {
                transport.stop();
            }
        }
    }

    /** 通知业务关闭；传输层重复关闭时不重复回调。 */
    private void close() {
        if (!closed) {
            closed = true;
            listener.onClose(context);
        }
    }

    /**
     * 为网络线程和取消回调绑定请求上下文，结束后恢复调用前的上下文。
     * 嵌套调用可能来自业务回调，不能无条件清空原有上下文。
     */
    private void inContext(Runnable action) {
        ChatContext previous = ChatContextHolder.currentContext();
        ChatContextHolder.set(chatContext);
        try {
            action.run();
        } finally {
            if (previous == null) {
                ChatContextHolder.clear();
            } else {
                ChatContextHolder.set(previous);
            }
        }
    }
}
