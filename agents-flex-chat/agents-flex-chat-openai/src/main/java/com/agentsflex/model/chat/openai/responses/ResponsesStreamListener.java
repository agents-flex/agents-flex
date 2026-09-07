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
import com.agentsflex.core.model.chat.*;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.*;
import com.alibaba.fastjson2.*;
import java.io.IOException;
import java.util.Map;

/** One bridge per request. Tool calls are delivered only in the authoritative terminal snapshot. */
class ResponsesStreamListener implements StreamClientListener {
    private final ChatContext chatContext;
    private final StreamClient transport;
    private final StreamResponseListener listener;
    private final StreamContext context;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private boolean opened;
    private boolean terminal;
    private boolean closed;

    ResponsesStreamListener(ChatModel model, ChatContext chatContext, StreamClient transport, StreamResponseListener listener) {
        this.chatContext = chatContext;
        this.transport = transport;
        this.listener = listener;
        this.context = new StreamContext(model, chatContext, new StreamClient() {
            @Override
            public void start(String url, Map<String, String> headers, String payload,
                                        StreamClientListener target, BaseChatConfig config) {
                throw new UnsupportedOperationException("Stream already started");
            }
            @Override
            public void stop() { cancel(); }
        });
    }

    @Override
    public synchronized void onStart(StreamClient client) {
        if (opened || terminal) return;
        opened = true;
        inContext(() -> listener.onOpen(context));
    }

    @Override
    public synchronized void onMessage(StreamClient client, String raw) {
        if (terminal) return;
        inContext(() -> {
            try {
                JSONObject event = JSON.parseObject(raw);
                String type = event.getString("type");
                if ("response.completed".equals(type) || "response.incomplete".equals(type) || "response.failed".equals(type)) {
                    AiMessageResponse response = new ResponsesResponseParser().parse(event.getString("response"), chatContext);
                    if (response.isError()) { fail(response.toException()); return; }
                    AiMessage full = response.getMessage();
                    full.setFullContent(full.getContent());
                    full.setFullReasoningContent(full.getReasoningContent());
                    full.setContent("");
                    full.setReasoningContent(null);
                    context.setFullMessage(full);
                    terminal = true;
                    try { listener.onMessage(context, response); } finally { try { close(); } finally { transport.stop(); } }
                } else if ("error".equals(type)) {
                    AiMessageResponse error = AiMessageResponse.error(chatContext, raw, event.getString("message"));
                    error.setErrorCode(event.getString("code"));
                    fail(error.toException());
                } else if ("response.output_text.delta".equals(type) || "response.reasoning_summary_text.delta".equals(type)) {
                    AiMessage delta = new AiMessage();
                    String value = event.getString("delta");
                    if (value == null) return;
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
            } catch (Exception error) { fail(error); }
        });
    }

    @Override
    public synchronized void onStop(StreamClient client) {
        inContext(() -> { if (!terminal) fail(new IOException("Responses stream ended before a terminal event")); else close(); });
    }

    @Override
    public synchronized void onFailure(StreamClient client, Throwable error) {
        inContext(() -> fail(error));
    }

    synchronized boolean isTerminal() { return terminal; }

    private synchronized void cancel() {
        inContext(() -> {
            if (terminal) return;
            terminal = true;
            try { close(); } finally { transport.stop(); }
        });
    }

    private void fail(Throwable error) {
        if (terminal) return;
        terminal = true;
        context.setThrowable(error);
        try { listener.onError(context, error); } finally { try { close(); } finally { transport.stop(); } }
    }

    private void close() {
        if (!closed) { closed = true; listener.onClose(context); }
    }

    private void inContext(Runnable action) {
        ChatContext previous = ChatContextHolder.currentContext();
        ChatContextHolder.set(chatContext);
        try { action.run(); } finally { if (previous == null) ChatContextHolder.clear(); else ChatContextHolder.set(previous); }
    }
}
