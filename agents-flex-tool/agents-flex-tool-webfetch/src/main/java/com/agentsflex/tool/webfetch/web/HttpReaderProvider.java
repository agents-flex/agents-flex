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
package com.agentsflex.tool.webfetch.web;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;

/**
 * HTTP Reader Provider。
 *
 * <p>
 * 通过标准 HTTP GET 请求获取网页原始内容。
 * </p>
 *
 * <h3>特点</h3>
 *
 * <ul>
 *     <li>实现简单</li>
 *     <li>性能最高</li>
 *     <li>无额外依赖</li>
 * </ul>
 *
 * <h3>局限性</h3>
 *
 * <ul>
 *     <li>无法执行 JavaScript</li>
 *     <li>无法处理 SPA 页面</li>
 *     <li>无法获取动态渲染内容</li>
 * </ul>
 *
 * <h3>适用场景</h3>
 *
 * <ul>
 *     <li>JSON API</li>
 *     <li>静态网页</li>
 *     <li>开放文档页面</li>
 * </ul>
 */
public class HttpReaderProvider implements WebReaderProvider {

    private final OkHttpClient client;

    public HttpReaderProvider(OkHttpClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public boolean supports(String url) {
        return true;
    }

    @Override
    public int score(String url) {
        if (url.endsWith(".json")) return 100;
        if (url.contains("docs")) return 80;

        return 50;
    }

    @Override
    public String read(String url) throws IOException {

        Request request = OKHttpUtil.defaultRequestBuilder(url).build();

        try (Response response = client.newCall(request).execute()) {

            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty body");
            }

            return OKHttpUtil.decodeBody(body);
        }
    }
}
