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
package com.agentsflex.llm.client.impl;

import com.agentsflex.llm.client.LlmClient;
import com.agentsflex.llm.client.LlmClientListener;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class AsyncHttpClient implements LlmClient {
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private OkHttpClient client;
    private LlmClientListener listener;
    private boolean isStop = false;

    @Override
    public void start(String url, Map<String, String> headers, String payload, LlmClientListener listener) {
        this.listener = listener;
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


        this.listener.onStart(this);
        this.client.newCall(rBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                AsyncHttpClient.this.listener.onFailure(AsyncHttpClient.this, e);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                AsyncHttpClient.this.listener.onMessage(AsyncHttpClient.this, response.message());
                if (!isStop) {
                    AsyncHttpClient.this.isStop = true;
                    AsyncHttpClient.this.listener.onStop(AsyncHttpClient.this);
                }
            }
        });
    }

    @Override
    public void stop() {
        if (!isStop) {
            this.isStop = true;
            client.dispatcher().executorService().shutdown();
            this.listener.onStop(this);
        }
    }


}
