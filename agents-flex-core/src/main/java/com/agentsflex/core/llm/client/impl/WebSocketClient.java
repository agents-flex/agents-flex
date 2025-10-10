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
package com.agentsflex.core.llm.client.impl;

import com.agentsflex.core.llm.LlmConfig;
import com.agentsflex.core.llm.client.LlmClient;
import com.agentsflex.core.llm.client.LlmClientListener;
import com.agentsflex.core.llm.client.OkHttpClientUtil;
import com.agentsflex.core.util.LogUtil;
import okhttp3.*;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public class WebSocketClient extends WebSocketListener implements LlmClient {

    private OkHttpClient okHttpClient;
    private WebSocket webSocket;
    private LlmClientListener listener;
    private LlmConfig config;
    private boolean isStop = false;
    private String payload;


    public WebSocketClient() {
        this(OkHttpClientUtil.buildDefaultClient());
    }

    public WebSocketClient(OkHttpClient okHttpClient) {
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
    public void start(String url, Map<String, String> headers, String payload, LlmClientListener listener, LlmConfig config) {
        if (isStop) {
            throw new IllegalStateException("WebSocketClient has been stopped and cannot be reused.");
        }

        this.listener = listener;
        this.payload = payload;
        this.config = config;
        this.isStop = false;

        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null && !headers.isEmpty()) {
            headers.forEach(builder::addHeader);
        }

        Request request = builder.build();

        // 创建 WebSocket 连接
        this.webSocket = okHttpClient.newWebSocket(request, this);

        if (config != null && config.isDebug()) {
            LogUtil.println(">>>>send payload:" + payload);
        }
    }

    @Override
    public void stop() {
        closeWebSocketAndNotify();
    }


    //webSocket events
    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        webSocket.send(payload);
        this.listener.onStart(this);
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        if (this.config.isDebug()) {
            LogUtil.println(">>>>receive payload:" + text);
        }
        this.listener.onMessage(this, text);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        this.onMessage(webSocket, bytes.utf8());
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        closeWebSocketAndNotify();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        try {
            Throwable failureThrowable = Util.getFailureThrowable(t, response);
            this.listener.onFailure(this, failureThrowable);
        } finally {
            closeWebSocketAndNotify();
        }
    }

    @Override
    public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        closeWebSocketAndNotify();
    }


    /**
     * 安全关闭 WebSocket 并通知监听器（确保只执行一次）
     */
    private void closeWebSocketAndNotify() {
        if (isStop) return;
        synchronized (this) {
            if (isStop) return;
            isStop = true;

            // 先通知 onStop
            if (this.listener != null) {
                try {
                    this.listener.onStop(this);
                } catch (Exception e) {
                    LogUtil.warn(e.getMessage(), e);
                }
            }

            // 再关闭 WebSocket（幂等：close 多次无害，但避免空指针）
            if (this.webSocket != null) {
                try {
                    this.webSocket.close(1000, ""); // 正常关闭
                } catch (Exception e) {
                    // 忽略关闭异常（连接可能已断）
                } finally {
                    this.webSocket = null;
                }
            }
        }
    }
}
