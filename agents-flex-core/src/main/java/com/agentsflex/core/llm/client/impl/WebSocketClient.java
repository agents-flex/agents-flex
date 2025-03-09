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

    private WebSocket webSocket;
    private LlmClientListener listener;
    private LlmConfig config;
    private boolean isStop = false;
    private String payload;

    @Override
    public void start(String url, Map<String, String> headers, String payload, LlmClientListener listener, LlmConfig config) {
        this.listener = listener;
        this.payload = payload;
        this.config = config;

        OkHttpClient client = OkHttpClientUtil.buildDefaultClient();

        Request request = new Request.Builder()
            .url(url)
            .build();

        this.isStop = false;
        this.webSocket = client.newWebSocket(request, this);

        if (this.config.isDebug()) {
            LogUtil.println(">>>>send payload:" + payload);
        }
    }

    @Override
    public void stop() {
        try {
            tryToStop();
        } finally {
            tryToCloseWebSocket();
        }
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
        try {
            tryToStop();
        } finally {
            tryToCloseWebSocket();
        }
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        try {
            try {
                Throwable failureThrowable = Util.getFailureThrowable(t, response);
                this.listener.onFailure(this, failureThrowable);
            } finally {
                tryToStop();
            }
        } finally {
            tryToCloseWebSocket();
        }
    }

    @Override
    public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        try {
            tryToStop();
        } finally {
            tryToCloseWebSocket();
        }
    }


    private void tryToCloseWebSocket() {
        if (this.webSocket != null) {
            this.webSocket.close(1000, "");
            this.webSocket = null;
        }
    }

    private boolean tryToStop() {
        if (!this.isStop) {
            this.isStop = true;
            this.listener.onStop(this);
            return true;
        }
        return false;
    }
}
