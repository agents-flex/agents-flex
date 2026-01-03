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
package com.agentsflex.core.model.client;

import com.agentsflex.core.util.StringUtil;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

public class OkHttpClientUtil {

    private static final Logger log = LoggerFactory.getLogger(OkHttpClientUtil.class);

    // 系统属性前缀
    private static final String PREFIX = "okhttp.";

    // 环境变量前缀（大写）
    private static final String ENV_PREFIX = "OKHTTP_";

    private static volatile OkHttpClient defaultClient;
    private static volatile OkHttpClient.Builder customBuilder;

    public static void setOkHttpClientBuilder(OkHttpClient.Builder builder) {
        if (defaultClient != null) {
            throw new IllegalStateException("OkHttpClient has already been initialized. " +
                "Please set the builder before first usage.");
        }
        customBuilder = builder;
    }

    public static OkHttpClient buildDefaultClient() {
        if (defaultClient == null) {
            synchronized (OkHttpClientUtil.class) {
                if (defaultClient == null) {
                    OkHttpClient.Builder builder = customBuilder != null
                        ? customBuilder
                        : createDefaultBuilder();
                    defaultClient = builder.build();
                    log.debug("OkHttpClient initialized with config: connectTimeout={}s, readTimeout={}s, writeTimeout={}s, " +
                            "connectionPool(maxIdle={}, keepAlive={}min)",
                        getConnectTimeout(), getReadTimeout(), getWriteTimeout(),
                        getMaxIdleConnections(), getKeepAliveMinutes());
                }
            }
        }
        return defaultClient;
    }

    private static OkHttpClient.Builder createDefaultBuilder() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(getConnectTimeout(), TimeUnit.SECONDS)
            .readTimeout(getReadTimeout(), TimeUnit.SECONDS)
            .writeTimeout(getWriteTimeout(), TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(getMaxIdleConnections(), getKeepAliveMinutes(), TimeUnit.MINUTES));

        configureProxy(builder);
        return builder;
    }

    // ==================== 配置读取方法 ====================

    private static int getConnectTimeout() {
        return getIntConfig("connectTimeout", "CONNECT_TIMEOUT", 60);
    }

    private static int getReadTimeout() {
        return getIntConfig("readTimeout", "READ_TIMEOUT", 300);
    }

    private static int getWriteTimeout() {
        return getIntConfig("writeTimeout", "WRITE_TIMEOUT", 60);
    }

    private static int getMaxIdleConnections() {
        return getIntConfig("connectionPool.maxIdleConnections", "CONNECTION_POOL_MAX_IDLE_CONNECTIONS", 5);
    }

    private static long getKeepAliveMinutes() {
        return getLongConfig("connectionPool.keepAliveMinutes", "CONNECTION_POOL_KEEP_ALIVE_MINUTES", 10);
    }

    private static String getProxyHost() {
        String host = getPropertyOrEnv("proxy.host", "PROXY_HOST", null);
        if (StringUtil.hasText(host)) return host.trim();

        // 兼容 Java 标准代理属性（作为 fallback）
        host = System.getProperty("https.proxyHost");
        if (StringUtil.hasText(host)) return host.trim();

        host = System.getProperty("http.proxyHost");
        if (StringUtil.hasText(host)) return host.trim();

        return null;
    }

    private static String getProxyPort() {
        String port = getPropertyOrEnv("proxy.port", "PROXY_PORT", null);
        if (StringUtil.hasText(port)) return port.trim();

        // 兼容 Java 标准代理属性
        port = System.getProperty("https.proxyPort");
        if (StringUtil.hasText(port)) return port.trim();

        port = System.getProperty("http.proxyPort");
        if (StringUtil.hasText(port)) return port.trim();

        return null;
    }

    // ==================== 工具方法 ====================

    private static int getIntConfig(String sysPropKey, String envKey, int defaultValue) {
        String value = getPropertyOrEnv(sysPropKey, envKey, null);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid integer value for '{}': '{}'. Using default: {}", fullSysPropKey(sysPropKey), value, defaultValue);
            return defaultValue;
        }
    }

    private static long getLongConfig(String sysPropKey, String envKey, long defaultValue) {
        String value = getPropertyOrEnv(sysPropKey, envKey, null);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid long value for '{}': '{}'. Using default: {}", fullSysPropKey(sysPropKey), value, defaultValue);
            return defaultValue;
        }
    }

    private static String getPropertyOrEnv(String sysPropKey, String envKey, String defaultValue) {
        // 1. 系统属性优先
        String value = System.getProperty(fullSysPropKey(sysPropKey));
        if (value != null) return value;

        // 2. 环境变量
        value = System.getenv(ENV_PREFIX + envKey);
        if (value != null) return value;

        return defaultValue;
    }

    private static String fullSysPropKey(String key) {
        return PREFIX + key;
    }

    // ==================== 代理配置 ====================

    private static void configureProxy(OkHttpClient.Builder builder) {
        String proxyHost = getProxyHost();
        String proxyPort = getProxyPort();

        if (StringUtil.hasText(proxyHost) && StringUtil.hasText(proxyPort)) {
            try {
                int port = Integer.parseInt(proxyPort);
                InetSocketAddress address = new InetSocketAddress(proxyHost, port);
                builder.proxy(new Proxy(Proxy.Type.HTTP, address));
                log.debug("HTTP proxy configured via config: {}:{}", proxyHost, port);
            } catch (NumberFormatException e) {
                log.warn("Invalid proxy port '{}'. Proxy will be ignored.", proxyPort, e);
            }
        }
    }
}
