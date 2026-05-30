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
package com.agentsflex.tool.commons.web;

import com.agentsflex.tool.commons.WebFetchTool;
import okhttp3.*;

import java.io.IOException;

/**
 * Jina Reader Provider。
 *
 * <p>
 * 基于：
 * </p>
 *
 * <pre>
 * https://r.jina.ai/
 * </pre>
 *
 * 对网页内容进行提取。
 *
 * <h3>特点</h3>
 *
 * <ul>
 *     <li>无需浏览器渲染</li>
 *     <li>自动去除广告和页面噪音</li>
 *     <li>适合博客、技术文档、文章阅读</li>
 * </ul>
 *
 */
public class JinaReaderProvider implements WebReaderProvider {

    private static final String ENDPOINT = "https://r.jina.ai/";

    private final OkHttpClient client;

    public JinaReaderProvider(OkHttpClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "jina";
    }

    @Override
    public boolean supports(String url) {
        return true;
    }

    @Override
    public int score(String url) {
        if (url.contains("zhihu.com")) return 100;
        if (url.contains("csdn.net")) return 90;
        if (url.contains("medium.com")) return 95;
        return 70;
    }

    @Override
    public String read(String url) throws IOException {
        if (!url.toLowerCase().startsWith("http")) {
            url = "http://" + url;
        }

        String jinaUrl = ENDPOINT + url;
        Request request = new Request.Builder()
            .url(jinaUrl)
            .get()
            .addHeader("User-Agent", WebFetchTool.USER_AGENT)
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Jina HTTP " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty Jina response");
            }

            return body.string();
        }
    }
}
