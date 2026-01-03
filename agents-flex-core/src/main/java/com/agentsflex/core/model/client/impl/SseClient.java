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
package com.agentsflex.core.model.client.impl;

import com.agentsflex.core.model.chat.ChatConfig;
import com.agentsflex.core.model.chat.log.ChatMessageLogger;
import com.agentsflex.core.model.client.OkHttpClientUtil;
import com.agentsflex.core.model.client.StreamClient;
import com.agentsflex.core.model.client.StreamClientListener;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class SseClient extends EventSourceListener implements StreamClient {

    private OkHttpClient okHttpClient;
    private EventSource eventSource;
    private StreamClientListener listener;
    private ChatConfig config;
    private boolean isStop = false;

    public SseClient() {
        this(OkHttpClientUtil.buildDefaultClient());
    }

    public SseClient(OkHttpClient okHttpClient) {
        if (okHttpClient == null) {
            throw new IllegalArgumentException("OkHttpClient must not be null");
        }
        this.okHttpClient = okHttpClient;
    }

    public OkHttpClient getOkHttpClient() {
        return okHttpClient;
    }

    public void setOkHttpClient(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    @Override
    public void start(String url, Map<String, String> headers, String payload, StreamClientListener listener, ChatConfig config) {
        this.listener = listener;
        this.config = config;
        this.isStop = false;

        Request.Builder builder = new Request.Builder()
            .url(url);

        if (headers != null && !headers.isEmpty()) {
            headers.forEach(builder::addHeader);
        }

        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(payload, mediaType);
        Request request = builder.post(body).build();


        EventSource.Factory factory = EventSources.createFactory(this.okHttpClient);
        this.eventSource = factory.newEventSource(request, this);

        ChatMessageLogger.logRequest(config, payload);

        if (this.listener != null) {
            this.listener.onStart(this);
        }

    }

    @Override
    public void stop() {
        tryToStop();
    }


    @Override
    public void onClosed(@NotNull EventSource eventSource) {
        tryToStop();
    }

    @Override
    public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
        ChatMessageLogger.logResponse(config, data);
        this.listener.onMessage(this, data);
    }

    @Override
    public void onFailure(@NotNull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
        try {
            this.listener.onFailure(this, Util.getFailureThrowable(t, response));
        } finally {
            tryToStop();
        }
    }

    @Override
    public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
        //super.onOpen(eventSource, response);
    }


    private void tryToStop() {
        if (!this.isStop) {
            try {
                this.isStop = true;
                this.listener.onStop(this);
            } finally {
                if (eventSource != null) {
                    eventSource.cancel();
                    eventSource = null;
                }
            }
        }
    }
}
