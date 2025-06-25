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
package com.agentsflex.core.llm.client;

import com.agentsflex.core.util.StringUtil;
import okhttp3.OkHttpClient;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

public class OkHttpClientUtil {

    private static OkHttpClient.Builder okHttpClientBuilder;

    public static OkHttpClient.Builder getOkHttpClientBuilder() {
        return okHttpClientBuilder;
    }

    public static void setOkHttpClientBuilder(OkHttpClient.Builder okHttpClientBuilder) {
        OkHttpClientUtil.okHttpClientBuilder = okHttpClientBuilder;
    }

    public static OkHttpClient buildDefaultClient() {
        if (okHttpClientBuilder != null) {
            return okHttpClientBuilder.build();
        }

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES);

        String proxyHost = System.getProperty("https.proxyHost");
        String proxyPort = System.getProperty("https.proxyPort");

        if (StringUtil.hasText(proxyHost) && StringUtil.hasText(proxyPort)) {
            InetSocketAddress inetSocketAddress = new InetSocketAddress(proxyHost.trim(), Integer.parseInt(proxyPort.trim()));
            builder.proxy(new Proxy(Proxy.Type.HTTP, inetSocketAddress));
        }

        return builder.build();
    }
}
