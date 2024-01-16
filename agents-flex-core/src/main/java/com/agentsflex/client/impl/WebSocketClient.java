/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.client.impl;

import com.agentsflex.client.LlmClient;
import com.agentsflex.client.LlmClientListener;
import okhttp3.*;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class WebSocketClient extends WebSocketListener implements LlmClient {


    private WebSocket webSocket;
    private LlmClientListener listener;
    private boolean isStop = false;

    private String payload;

    @Override
    public void start(String url, Map<String, String> headers, String payload, LlmClientListener listener) {
        this.listener = listener;
        this.payload = payload;

        OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();

        Request request = new Request.Builder()
            .url(url)
            .build();

        this.webSocket = client.newWebSocket(request, this);
        this.isStop = false;
    }

    @Override
    public void stop() {
        if (webSocket != null) {
            webSocket.close(0, "");
        }
        if (!isStop) {
            this.isStop = true;
            listener.onStop(this);
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
        this.listener.onMessage(this, text);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        this.listener.onMessage(this, bytes.utf8());
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        if (!isStop) {
            this.isStop = true;
            this.listener.onStop(this);

            this.listener.onFailure(this, t);
        }
    }

    @Override
    public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
        if (!isStop) {
            this.isStop = true;
            this.listener.onStop(this);
        }
    }
}
