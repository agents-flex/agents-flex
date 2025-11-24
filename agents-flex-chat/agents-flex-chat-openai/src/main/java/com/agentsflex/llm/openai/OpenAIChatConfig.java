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
package com.agentsflex.llm.openai;

import com.agentsflex.core.model.chat.ChatConfig;
import com.agentsflex.core.model.chat.ChatInterceptor;
import com.agentsflex.core.util.StringUtil;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 聊天模型的配置类，支持通过 Builder 模式创建配置或直接构建 {@link OpenAIChatModel}。
 * <p>
 * 默认值：
 * <ul>
 *   <li>provider: {@code "openai"}</li>
 *   <li>model: {@code "gpt-3.5-turbo"}</li>
 *   <li>endpoint: {@code "https://api.openai.com"}</li>
 *   <li>requestPath: {@code "/v1/chat/completions"}</li>
 * </ul>
 * <p>
 * 该配置类专为 OpenAI 兼容 API 设计，适用于 OpenAI 官方、Azure OpenAI 或其他兼容服务。
 */
public class OpenAIChatConfig extends ChatConfig {

    private static final String DEFAULT_PROVIDER = "openai";
    private static final String DEFAULT_MODEL = "gpt-3.5-turbo";
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com";
    private static final String DEFAULT_REQUEST_PATH = "/v1/chat/completions";

    public OpenAIChatConfig() {
        setProvider(DEFAULT_PROVIDER);
        setEndpoint(DEFAULT_ENDPOINT);
        setRequestPath(DEFAULT_REQUEST_PATH);
        setModel(DEFAULT_MODEL);
    }

    /**
     * 创建一个 {@link OpenAIChatModel} 实例，使用当前配置。
     *
     * @return 新的 {@link OpenAIChatModel} 实例
     */
    public final OpenAIChatModel toChatModel() {
        return new OpenAIChatModel(this);
    }

    /**
     * 创建一个 {@link OpenAIChatModel} 实例，使用当前配置和指定的实例级拦截器。
     *
     * @param interceptors 实例级拦截器列表，可为 {@code null} 或空列表
     * @return 新的 {@link OpenAIChatModel} 实例
     */
    public final OpenAIChatModel toChatModel(List<ChatInterceptor> interceptors) {
        return new OpenAIChatModel(this, interceptors);
    }


    /**
     * 构建器类，用于流畅地创建 {@link OpenAIChatConfig} 或直接构建 {@link OpenAIChatModel}。
     */
    public static class Builder {
        private final OpenAIChatConfig config = new OpenAIChatConfig();

        // --- BaseModelConfig fields ---

        public Builder apiKey(String apiKey) {
            config.setApiKey(apiKey);
            return this;
        }

        public Builder provider(String provider) {
            config.setProvider(provider);
            return this;
        }

        public Builder endpoint(String endpoint) {
            config.setEndpoint(endpoint);
            return this;
        }

        public Builder requestPath(String requestPath) {
            config.setRequestPath(requestPath);
            return this;
        }

        public Builder model(String model) {
            config.setModel(model);
            return this;
        }

        /**
         * 添加单个自定义属性（会进行深拷贝，不会持有外部引用）。
         */
        public Builder customProperty(String key, Object value) {
            config.putCustomProperty(key, value);
            return this;
        }

        /**
         * 设置自定义属性映射（会进行深拷贝，不会持有外部 map 引用）。
         */
        public Builder customProperties(Map<String, Object> customProperties) {
            config.setCustomProperties(customProperties);
            return this;
        }

        // --- ChatConfig fields ---

        public Builder supportImage(Boolean supportImage) {
            config.setSupportImage(supportImage);
            return this;
        }

        public Builder supportImageBase64Only(Boolean supportImageBase64Only) {
            config.setSupportImageBase64Only(supportImageBase64Only);
            return this;
        }

        public Builder supportAudio(Boolean supportAudio) {
            config.setSupportAudio(supportAudio);
            return this;
        }

        public Builder supportVideo(Boolean supportVideo) {
            config.setSupportVideo(supportVideo);
            return this;
        }

        public Builder supportTool(Boolean supportTool) {
            config.setSupportTool(supportTool);
            return this;
        }

        public Builder supportThinking(Boolean supportThinking) {
            config.setSupportThinking(supportThinking);
            return this;
        }

        public Builder thinkingEnabled(boolean thinkingEnabled) {
            config.setThinkingEnabled(thinkingEnabled);
            return this;
        }

        public Builder observabilityEnabled(boolean observabilityEnabled) {
            config.setObservabilityEnabled(observabilityEnabled);
            return this;
        }

        public Builder logEnabled(boolean logEnabled) {
            config.setLogEnabled(logEnabled);
            return this;
        }

        public Builder headersConfig(Consumer<Map<String, String>> headersConfig) {
            config.setHeadersConfig(headersConfig);
            return this;
        }

        // --- Build methods ---

        /**
         * 构建 {@link OpenAIChatConfig} 配置对象。
         * <p>
         * 该方法会校验必要字段（如 {@code apiKey}），若缺失将抛出异常。
         *
         * @return 构建完成的配置对象
         * @throws IllegalStateException 如果 {@code apiKey} 未设置或为空
         */
        public OpenAIChatConfig build() {
            if (StringUtil.noText(config.getApiKey())) {
                throw new IllegalStateException("apiKey must be set for OpenAIChatConfig");
            }
            return config;
        }

        /**
         * 直接构建 {@link OpenAIChatModel} 实例，使用默认（全局）拦截器。
         *
         * @return 新的聊天模型实例
         * @throws IllegalStateException 如果 {@code apiKey} 未设置或为空
         */
        public OpenAIChatModel buildModel() {
            return new OpenAIChatModel(build());
        }

        /**
         * 直接构建 {@link OpenAIChatModel} 实例，并指定实例级拦截器。
         *
         * @param interceptors 实例级拦截器列表，可为 {@code null} 或空
         * @return 新的聊天模型实例
         * @throws IllegalStateException 如果 {@code apiKey} 未设置或为空
         */
        public OpenAIChatModel buildModel(List<ChatInterceptor> interceptors) {
            return new OpenAIChatModel(build(), interceptors);
        }
    }

    /**
     * 获取一个新的构建器实例，用于链式配置。
     *
     * @return {@link Builder} 实例
     */
    public static Builder builder() {
        return new Builder();
    }
}
