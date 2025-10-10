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

    // 使用 volatile + 双重检查锁实现线程安全的懒加载单例
    private static volatile OkHttpClient defaultClient;
    private static volatile OkHttpClient.Builder customBuilder;

    /**
     * 设置自定义 OkHttpClient.Builder（必须在首次调用 buildDefaultClient() 前设置）
     */
    public static void setOkHttpClientBuilder(OkHttpClient.Builder builder) {
        if (defaultClient != null) {
            throw new IllegalStateException("OkHttpClient has already been initialized. " +
                "Please set the builder before first usage.");
        }
        customBuilder = builder;
    }

    /**
     * 获取默认的 OkHttpClient 单例（线程安全）
     */
    public static OkHttpClient buildDefaultClient() {
        if (defaultClient == null) {
            synchronized (OkHttpClientUtil.class) {
                if (defaultClient == null) {
                    OkHttpClient.Builder builder = customBuilder != null
                        ? customBuilder
                        : createDefaultBuilder();
                    defaultClient = builder.build();
                }
            }
        }
        return defaultClient;
    }

    /**
     * 创建默认的 OkHttpClient.Builder（含超时和代理配置）
     */
    private static OkHttpClient.Builder createDefaultBuilder() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES);

        String proxyHost = System.getProperty("https.proxyHost");
        String proxyPort = System.getProperty("https.proxyPort");

        if (StringUtil.hasText(proxyHost) && StringUtil.hasText(proxyPort)) {
            try {
                int port = Integer.parseInt(proxyPort.trim());
                InetSocketAddress address = new InetSocketAddress(proxyHost.trim(), port);
                builder.proxy(new Proxy(Proxy.Type.HTTP, address));
            } catch (NumberFormatException e) {
                // 忽略无效的代理端口，使用默认无代理配置
                // 可选：记录警告日志
            }
        }

        return builder;
    }
}
