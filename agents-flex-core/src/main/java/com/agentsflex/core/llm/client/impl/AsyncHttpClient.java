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
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class AsyncHttpClient implements LlmClient {
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private OkHttpClient client;
    private LlmClientListener listener;
    private LlmConfig config;
    private boolean isStop = false;

    @Override
    public void start(String url, Map<String, String> headers, String payload, LlmClientListener listener, LlmConfig config) {
        this.listener = listener;
        this.config = config;
        this.isStop = false;

        Request.Builder rBuilder = new Request.Builder()
            .url(url);

        if (headers != null && !headers.isEmpty()) {
            headers.forEach(rBuilder::addHeader);
        }

        RequestBody body = RequestBody.create(payload, JSON_TYPE);
        rBuilder.post(body);

        this.client = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.MINUTES)
            .readTimeout(3, TimeUnit.MINUTES)
            .build();

        if (this.config.isDebug()) {
            System.out.println(">>>>send payload:" + payload);
        }

        this.listener.onStart(this);
        this.client.newCall(rBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                try {
                    AsyncHttpClient.this.listener.onFailure(AsyncHttpClient.this, e);
                } finally {
                    tryToStop();
                }
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                if (config.isDebug()) {
                    System.out.println(">>>>receive payload:" + response.message());
                }
                try {
                    AsyncHttpClient.this.listener.onMessage(AsyncHttpClient.this, response.message());
                } finally {
                    response.close();
                    tryToStop();
                }
            }
        });
    }


    @Override
    public void stop() {
        tryToStop();
    }


    private boolean tryToStop() {
        if (!this.isStop) {
            try {
                this.isStop = true;
                this.listener.onStop(this);
            } finally {
                if (client != null) {
                    client.dispatcher().executorService().shutdown();
                }
            }
            return true;
        }
        return false;
    }


}
