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

    public DnjsonClient() {
        this(OkHttpClientUtil.buildDefaultClient());
    }

    public DnjsonClient(OkHttpClient okHttpClient) {
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
            throw new IllegalStateException("DnjsonClient has been stopped and cannot be reused.");
        }

        this.listener = listener;
        this.config = config;
        this.isStop = false;

        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null && !headers.isEmpty()) {
            headers.forEach(builder::addHeader);
        }

        RequestBody body = RequestBody.create(payload, JSON_TYPE);
        Request request = builder.post(body).build();

        if (config != null && config.isDebug()) {
            LogUtil.println(">>>> send payload: {}", payload);
        }

        if (this.listener != null) {
            try {
                this.listener.onStart(this);
            } catch (Exception e) {
                LogUtil.warn("Error in listener.onStart", e);
                return; // 可选：是否继续请求？
            }
        }

        // 发起异步请求
        okHttpClient.newCall(request).enqueue(this);
    }

    @Override
    public void stop() {
        // 注意：OkHttp 的 Call 无法取消已开始的 onResponse
        // 所以 stop() 主要用于标记状态，防止后续回调处理
        markAsStopped();
    }


    @Override
    public void onFailure(@NotNull Call call, @NotNull IOException e) {
        try {
            if (listener != null && !isStop) {
                Throwable error = Util.getFailureThrowable(e, null);
                listener.onFailure(this, error);
            }
        } catch (Exception ex) {
            LogUtil.warn("Error in listener.onFailure", ex);
        } finally {
            markAsStopped();
        }
    }

    @Override
    public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
        try {
            if (!response.isSuccessful()) {
                if (listener != null && !isStop) {
                    Throwable error = Util.getFailureThrowable(null, response);
                    listener.onFailure(this, error);
                }
                return;
            }

            ResponseBody body = response.body();
            if (body == null || isStop) {
                return;
            }

            // 使用 try-with-resources 确保流关闭
            try (ResponseBody responseBody = body;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody.byteStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (isStop) break; // 支持中途 stop()

                    if (!StringUtil.hasText(line)) continue;

                    String jsonLine = StringUtil.isJsonObject(line) ? line : "{" + line + "}";

                    if (listener != null && !isStop) {
                        try {
                            listener.onMessage(this, jsonLine);
                        } catch (Exception e) {
                            LogUtil.warn("Error in listener.onMessage", e);
                        }
                    }
                }
            }
        } finally {
            markAsStopped();
        }
    }


    private void markAsStopped() {
        if (isStop) return;
        synchronized (this) {
            if (isStop) return;
            isStop = true;
            if (listener != null) {
                try {
                    listener.onStop(this);
                } catch (Exception e) {
                    LogUtil.warn("Error in listener.onStop", e);
                }
            }
        }
    }
}
