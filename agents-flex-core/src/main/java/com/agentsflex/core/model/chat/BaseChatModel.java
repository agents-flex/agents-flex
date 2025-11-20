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
package com.agentsflex.core.model.chat;

import com.agentsflex.core.model.chat.interceptor.ChatInterceptor;
import com.agentsflex.core.model.chat.interceptor.GlobalChatInterceptors;
import com.agentsflex.core.model.chat.interceptor.StreamChain;
import com.agentsflex.core.model.chat.interceptor.SyncChain;
//import com.agentsflex.core.model.chat.interceptor.impl.ObservabilityInterceptor;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.ChatClient;
import com.agentsflex.core.prompt.Prompt;

import java.util.*;

/**
 * 支持责任链、统一上下文和协议客户端的聊天模型基类。
 * <p>
 * 该类为所有具体的 LLM 实现（如 OpenAI、Qwen、Ollama）提供统一入口，并集成：
 * <ul>
 *   <li><b>责任链模式</b>：通过 {@link ChatInterceptor} 实现请求拦截、监控、日志等横切逻辑</li>
 *   <li><b>线程上下文管理</b>：通过 {@link ChatContextHolder} 在整个调用链中传递上下文信息</li>
 *   <li><b>协议执行抽象</b>：通过 {@link ChatClient} 解耦协议细节，支持 HTTP/gRPC/WebSocket 等</li>
 *   <li><b>可观测性</b>：自动集成 OpenTelemetry（通过 {@link ObservabilityInterceptor}）</li>
 * </ul>
 *
 * <h2>架构流程</h2>
 * <ol>
 *   <li>调用 {@link #chat(Prompt, ChatOptions)} 或 {@link #chatStream(Prompt, StreamResponseListener, ChatOptions)}</li>
 *   <li>构建请求上下文（URL/Headers/Body）并初始化 {@link ChatContext}</li>
 *   <li>构建责任链：可观测性拦截器 → 全局拦截器 → 用户拦截器</li>
 *   <li>责任链执行：每个拦截器可修改 {@link ChatContext}，最后由 {@link ChatClient} 执行实际调用</li>
 *   <li>结果返回给调用方</li>
 * </ol>
 *
 * <h2>子类实现指南</h2>
 * <ul>
 *   <li>必须实现 {@link #buildRequestBody(Prompt, ChatOptions, boolean)}：构建 LLM 特定的请求体</li>
 *   <li>必须实现 {@link #buildClient(ChatContext)}：根据上下文创建具体的 {@link ChatClient}</li>
 *   <li>可选重写 {@link #buildRequestUrl()} 和 {@link #buildHeaders(Prompt, ChatOptions)}：自定义 URL 和 Headers</li>
 * </ul>
 *
 * @param <T> 具体的配置类型，必须是 {@link ChatConfig} 的子类
 */
public abstract class BaseChatModel<T extends ChatConfig> implements ChatModel {

    /**
     * 聊天模型配置，包含 API Key、Endpoint、Model 等信息
     */
    protected final T config;

    /**
     * 拦截器链，按执行顺序存储（可观测性 → 全局 → 用户）
     */
    private final List<ChatInterceptor> interceptors;

    /**
     * 构造一个聊天模型实例，不使用实例级拦截器。
     *
     * @param config 聊天模型配置
     */
    public BaseChatModel(T config) {
        this(config, Collections.emptyList());
    }

    /**
     * 构造一个聊天模型实例，并指定实例级拦截器。
     * <p>
     * 实例级拦截器会与全局拦截器（通过 {@link GlobalChatInterceptors} 注册）合并，
     * 执行顺序为：可观测性拦截器 → 全局拦截器 → 实例拦截器。
     *
     * @param config           聊天模型配置
     * @param userInterceptors 实例级拦截器列表
     */
    public BaseChatModel(T config, List<ChatInterceptor> userInterceptors) {
        this.config = config;
        this.interceptors = buildInterceptorChain(userInterceptors);
    }

    /**
     * 构建完整的拦截器链。
     * <p>
     * 执行顺序：
     * 1. 可观测性拦截器（最外层，最早执行）
     * 2. 全局拦截器（通过 GlobalChatInterceptors 注册）
     * 3. 用户拦截器（实例级）
     *
     * @param userInterceptors 用户提供的拦截器列表
     * @return 按执行顺序排列的拦截器链
     */
    private List<ChatInterceptor> buildInterceptorChain(List<ChatInterceptor> userInterceptors) {
        List<ChatInterceptor> chain = new ArrayList<>();

        // 1. 可观测性拦截器（最外层）
        // 仅在配置启用时添加，负责 OpenTelemetry 追踪和指标上报
        if (config.isObservabilityEnabled()) {
//            chain.add(new ObservabilityInterceptor());
        }

        // 2. 全局拦截器（通过 GlobalChatInterceptors 注册）
        // 适用于所有聊天模型实例的通用逻辑（如全局日志、认证）
        chain.addAll(GlobalChatInterceptors.getInterceptors());

        // 3. 用户拦截器（实例级）
        // 适用于当前实例的特定逻辑
        if (userInterceptors != null) {
            chain.addAll(userInterceptors);
        }

        return chain;
    }

    /**
     * 执行同步聊天请求。
     * <p>
     * 流程：
     * 1. 构建请求上下文（URL/Headers/Body）
     * 2. 初始化线程上下文 {@link ChatContext}
     * 3. 构建并执行责任链
     * 4. 返回 LLM 响应
     *
     * @param prompt  用户输入的提示
     * @param options 聊天选项（如流式开关、超时等）
     * @return LLM 响应结果
     */
    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        // 构建请求体（由子类实现）
        String requestBody = buildRequestBody(prompt, options, false);
        // 构建请求头（可由子类重写）
        Map<String, String> requestHeaders = buildHeaders(prompt, options);

        // 初始化聊天上下文（自动清理）
        try (ChatContextHolder.ChatContextScope scope =
                 ChatContextHolder.beginChat(config, options, prompt,
                     buildRequestUrl(), requestHeaders, requestBody)) {

            // 构建同步责任链并执行
            SyncChain chain = buildSyncChain(0);
            return chain.proceed(this, scope.context);
        }
    }

    /**
     * 执行流式聊天请求。
     * <p>
     * 流程与同步请求类似，但返回结果通过回调方式分片返回。
     *
     * @param prompt   用户输入的提示
     * @param listener 流式响应监听器
     * @param options  聊天选项
     */
    @Override
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        String requestBody = buildRequestBody(prompt, options, true);
        Map<String, String> requestHeaders = buildHeaders(prompt, options);

        try (ChatContextHolder.ChatContextScope scope =
                 ChatContextHolder.beginChat(config, options, prompt,
                     buildRequestUrl(), requestHeaders, requestBody)) {

            StreamChain chain = buildStreamChain(0);
            chain.proceed(this, scope.context, listener);
        }
    }

    /**
     * 构建请求 URL。
     * <p>
     * 默认实现返回 {@code config.getFullUrl()}，子类可重写以支持特殊 URL 格式。
     *
     * @return 请求目标地址
     */
    protected String buildRequestUrl() {
        return config.getFullUrl();
    }

    /**
     * 构建请求头。
     * <p>
     * 默认实现包含 Content-Type 和 Authorization，子类可重写以添加自定义头。
     *
     * @param prompt  用户提示
     * @param options 聊天选项
     * @return 请求头映射
     */
    protected Map<String, String> buildHeaders(Prompt prompt, ChatOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());
        return headers;
    }

    /**
     * 构建请求体。
     * <p>
     * <b>子类必须实现此方法</b>，根据 LLM 协议格式化请求体。
     *
     * @param prompt    用户提示
     * @param options   聊天选项
     * @param streaming 是否为流式请求
     * @return 序列化后的请求体（通常是 JSON 字符串）
     */
    protected abstract String buildRequestBody(Prompt prompt, ChatOptions options, boolean streaming);

    /**
     * 构建同步责任链。
     * <p>
     * 递归构建拦截器链，链尾节点负责创建并调用 {@link ChatClient}。
     *
     * @param index 当前拦截器索引
     * @return 同步责任链
     */
    private SyncChain buildSyncChain(int index) {
        // 链尾：执行实际 LLM 调用
        if (index >= interceptors.size()) {
            return (model, context) -> {
                // 创建协议客户端（由子类实现）
                ChatClient client = buildClient(context);
                // 执行同步调用
                return client.chat();
            };
        }

        // 递归构建下一个节点
        ChatInterceptor current = interceptors.get(index);
        SyncChain next = buildSyncChain(index + 1);

        // 当前节点：执行拦截器逻辑
        return (model, context) -> current.intercept(model, context, next);
    }

    /**
     * 构建流式责任链。
     * <p>
     * 与同步链类似，但支持流式监听器。
     *
     * @param index 当前拦截器索引
     * @return 流式责任链
     */
    private StreamChain buildStreamChain(int index) {
        if (index >= interceptors.size()) {
            return (model, context, listener) -> {
                ChatClient client = buildClient(context);
                client.chatStream(listener);
            };
        }

        ChatInterceptor current = interceptors.get(index);
        StreamChain next = buildStreamChain(index + 1);
        return (model, context, listener) -> current.interceptStream(model, context, listener, next);
    }

    /**
     * 动态添加拦截器。
     * <p>
     * 新拦截器会被添加到链的末尾（在用户拦截器区域）。
     *
     * @param interceptor 要添加的拦截器
     */
    public void addInterceptor(ChatInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    /**
     * 获取聊天模型配置。
     *
     * @return 聊天配置对象
     */
    public T getConfig() {
        return config;
    }

    /**
     * 创建协议客户端。
     * <p>
     * <b>子类必须实现此方法</b>，根据 {@link ChatContext} 创建具体的 {@link ChatClient} 实例。
     * <p>
     * 注意：此方法会在责任链末端被调用，此时 {@link ChatContext} 可能已被拦截器修改。
     *
     * @param context 聊天上下文（包含完整的请求信息）
     * @return 协议客户端实例
     */
    public abstract ChatClient buildClient(ChatContext context);
}
