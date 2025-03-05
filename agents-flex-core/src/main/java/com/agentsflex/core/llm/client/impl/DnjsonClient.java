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
import com.agentsflex.core.util.StringUtil;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;

public class DnjsonClient implements LlmClient, Callback {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private OkHttpClient okHttpClient;
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

        this.okHttpClient = OkHttpClientUtil.buildDefaultClient();

        if (this.config.isDebug()) {
            System.out.println(">>>>send payload:" + payload);
        }


        this.listener.onStart(this);
        this.okHttpClient.newCall(rBuilder.build()).enqueue(this);
    }

    @Override
    public void stop() {
        tryToStop();
    }


    @Override
    public void onFailure(@NotNull Call call, @NotNull IOException e) {
        try {
            this.listener.onFailure(this, Util.getFailureThrowable(e, null));
        } finally {
            tryToStop();
        }
    }

    @Override
    public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
        if (!response.isSuccessful()) {
            tryToStop();
            return;
        }
        ResponseBody body = response.body();
        if (body == null) {
            tryToStop();
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream()))) {
            String line = reader.readLine();
            while (StringUtil.hasText(line)) {
                try {
                    if (StringUtil.isJsonObject(line)) {
                        this.listener.onMessage(this, line);
                    } else {
                        this.listener.onMessage(this, "{" + line + "}");
                    }
                } finally {
                    line = reader.readLine();
                }
            }
        } finally {
            tryToStop();
        }
    }


    private boolean tryToStop() {
        if (!this.isStop) {
            try {
                this.isStop = true;
                this.listener.onStop(this);
            } finally {
                if (okHttpClient != null) {
                    okHttpClient.dispatcher().executorService().shutdown();
                }
            }
            return true;
        }
        return false;
    }
}
