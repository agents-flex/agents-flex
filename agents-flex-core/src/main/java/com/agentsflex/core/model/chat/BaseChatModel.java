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
package com.agentsflex.core.model.chat;

import com.agentsflex.core.model.chat.log.ChatMessageLogger;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.ChatClient;
import com.agentsflex.core.model.client.ChatRequestSpec;
import com.agentsflex.core.model.client.ChatRequestSpecBuilder;
import com.agentsflex.core.prompt.Prompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 支持责任链、统一上下文和协议客户端的聊天模型基类。
 * <p>
 * 该类为所有具体的 LLM 实现（如 OpenAI、Qwen、Ollama）提供统一入口，并集成：
 * <ul>
 *   <li><b>责任链模式</b>：通过 {@link ChatInterceptor} 实现请求拦截、监控、日志等横切逻辑</li>
 *   <li><b>线程上下文管理</b>：通过 {@link ChatContextHolder} 在整个调用链中传递上下文信息</li>
 *   <li><b>协议执行抽象</b>：通过 {@link ChatClient} 解耦协议细节，支持 HTTP/gRPC/WebSocket 等</li>
 *   <li><b>可观测性</b>：自动集成 OpenTelemetry（通过 {@link ChatObservabilityInterceptor}）</li>
 * </ul>
 *
 * <h2>架构流程</h2>
 * <ol>
 *   <li>调用 {@link #chat(Prompt, ChatOptions)} 或 {@link #chatStream(Prompt, StreamResponseListener, ChatOptions)}</li>
 *   <li>构建请求上下文（URL/Headers）并初始化 {@link ChatContext}</li>
 *   <li>合并框架、全局和实例 Registration，并按 order 构建责任链</li>
 *   <li>责任链执行：每个拦截器可修改 {@link ChatContext}，最后由 {@link ChatClient} 执行实际调用</li>
 *   <li>结果返回给调用方</li>
 * </ol>
 *
 * @param <T> 具体的配置类型，必须是 {@link BaseChatConfig} 的子类
 */
public abstract class BaseChatModel<T extends BaseChatConfig> implements ChatModel {

    /**
     * 聊天模型配置，包含 API Key、Endpoint、Model 等信息
     */
    protected final T config;
    protected ChatClient chatClient;
    protected ChatRequestSpecBuilder chatRequestSpecBuilder;

    /**
     * 应用拦截器注册列表（全局 → 用户）。每次请求会与框架注册合并并排序。
     */
    private final List<ChatInterceptorRegistration> interceptorRegistrations;

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
     * 最终顺序由 {@link ChatInterceptorRegistration#getOrder()} 决定。
     *
     * @param config           聊天模型配置
     * @param userInterceptors 实例级拦截器列表
     */
    public BaseChatModel(T config, List<ChatInterceptor> userInterceptors) {
        this.config = config;
        this.interceptorRegistrations = buildInterceptorChain(userInterceptors);
    }

    /**
     * 构建应用拦截器注册列表。框架拦截器在每次请求创建责任链快照时合并。
     *
     * @param userInterceptors 用户提供的拦截器列表
     * @return 按执行顺序排列的拦截器链
     */
    private List<ChatInterceptorRegistration> buildInterceptorChain(List<ChatInterceptor> userInterceptors) {

        // 1. 全局拦截器（通过 GlobalChatInterceptors 注册）
        // 适用于所有聊天模型实例的通用逻辑（如全局日志、认证）
        List<ChatInterceptorRegistration> chain = new ArrayList<>(
            GlobalChatInterceptors.getRegistrations());

        // 2. 用户拦截器（实例级）
        // 适用于当前实例的特定逻辑
        if (userInterceptors != null) {
            for (ChatInterceptor interceptor : userInterceptors) {
                chain.add(ChatInterceptorRegistration.of(interceptor));
            }
        }

        return chain;
    }

    /**
     * 执行同步聊天请求。
     * <p>
     * 流程：
     * 1. 构建不包含 Body 的传输配置并初始化 {@link ChatContext}
     * 2. 构建并执行责任链，拦截器可修改 Prompt、ChatOptions 和传输配置
     * 3. 在责任链末端根据最终上下文构建 Body 并调用模型
     * 4. 返回 LLM 响应
     *
     * @param prompt  用户输入的提示
     * @param options 聊天选项（如流式开关、超时等）
     * @return LLM 响应结果
     */
    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        if (options == null) {
            options = new ChatOptions();
        }
        options.setStreaming(false);
        ChatRequestSpec request = getChatRequestSpecBuilder().buildRequestSpec(prompt, options, config);
        // 初始化聊天上下文（自动清理）
        try (ChatContextHolder.ChatContextScope scope =
                 ChatContextHolder.beginChat(prompt, options, request, config)) {
            // 构建同步责任链并执行
            SyncChain chain = buildSyncChain(buildRequestInterceptorChain(), 0);
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
        if (options == null) {
            options = new ChatOptions();
        }
        options.setStreaming(true);
        ChatRequestSpec request = getChatRequestSpecBuilder().buildRequestSpec(prompt, options, config);
        try (ChatContextHolder.ChatContextScope scope =
                 ChatContextHolder.beginChat(prompt, options, request, config)) {
            StreamChain chain = buildStreamChain(buildRequestInterceptorChain(), 0);
            chain.proceed(this, scope.context, listener);
        }
    }

    private String buildRequestBody(ChatContext context, boolean streaming) {
        ChatOptions requestOptions = context.getOptions();
        if (requestOptions == null) {
            requestOptions = new ChatOptions();
            context.setOptions(requestOptions);
        }
        requestOptions.setStreaming(streaming);

        BaseChatConfig requestConfig = context.getConfig();
        if (requestConfig == null) {
            requestConfig = config;
            context.setConfig(requestConfig);
        }
        context.refreshContextFromOptions();
        return getChatRequestSpecBuilder().buildRequestBody(
            context.getPrompt(), requestOptions, requestConfig);
    }


    /** Builds and stably sorts the effective registration snapshot for one request. */
    private List<ChatInterceptorRegistration> buildRequestInterceptorChain() {
        List<ChatInterceptorRegistration> chain = new ArrayList<>();
        chain.addAll(FrameworkChatInterceptors.getRegistrations());
        chain.addAll(interceptorRegistrations);
        chain.sort(Comparator.comparingInt(ChatInterceptorRegistration::getOrder));
        return chain;
    }

    /**
     * 构建同步责任链。
     * <p>
     * 递归构建拦截器链，链尾节点负责创建并调用 {@link ChatClient}。
     *
     * @param registrations 当前请求的拦截器注册快照
     * @param index 当前拦截器索引
     * @return 同步责任链
     */
    private SyncChain buildSyncChain(List<ChatInterceptorRegistration> registrations, int index) {
        // 链尾：执行实际 LLM 调用
        if (index >= registrations.size()) {
            return (model, context) -> {
                AiMessageResponse aiMessageResponse = null;
                String body = buildRequestBody(context, false);
                try {
                    ChatMessageLogger.logRequest(model.getConfig(), body);
                    aiMessageResponse = getChatClient().chat(body);
                    return aiMessageResponse;
                } finally {
                    ChatMessageLogger.logResponse(model.getConfig(), aiMessageResponse == null ? "" : aiMessageResponse.getRawText());
                }
            };
        }

        // 递归构建下一个节点
        ChatInterceptorRegistration current = registrations.get(index);
        SyncChain next = buildSyncChain(registrations, index + 1);

        // 当前节点：执行拦截器逻辑
        return (model, context) -> {
            if (!current.matches(context)) {
                return next.proceed(model, context);
            }
            return current.getInterceptor().intercept(model, context, next);
        };
    }

    /**
     * 构建流式责任链。
     * <p>
     * 与同步链类似，但支持流式监听器。
     *
     * @param registrations 当前请求的拦截器注册快照
     * @param index 当前拦截器索引
     * @return 流式责任链
     */
    private StreamChain buildStreamChain(List<ChatInterceptorRegistration> registrations, int index) {
        if (index >= registrations.size()) {
            return (model, context, listener) -> {
                String body = buildRequestBody(context, true);
                getChatClient().chatStream(body, listener);
            };
        }

        ChatInterceptorRegistration current = registrations.get(index);
        StreamChain next = buildStreamChain(registrations, index + 1);
        return (model, context, listener) -> {
            if (!current.matches(context)) {
                next.proceed(model, context, listener);
                return;
            }
            current.getInterceptor().interceptStream(model, context, listener, next);
        };
    }


    public T getConfig() {
        return config;
    }

    public ChatClient getChatClient() {
        return chatClient;
    }

    public void setChatClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public ChatRequestSpecBuilder getChatRequestSpecBuilder() {
        return chatRequestSpecBuilder;
    }

    public void setChatRequestSpecBuilder(ChatRequestSpecBuilder chatRequestSpecBuilder) {
        this.chatRequestSpecBuilder = chatRequestSpecBuilder;
    }

    public List<ChatInterceptor> getInterceptors() {
        List<ChatInterceptor> interceptors = new ArrayList<>(interceptorRegistrations.size());
        for (ChatInterceptorRegistration registration : interceptorRegistrations) {
            interceptors.add(registration.getInterceptor());
        }
        return Collections.unmodifiableList(interceptors);
    }

    public List<ChatInterceptorRegistration> getInterceptorRegistrations() {
        return Collections.unmodifiableList(new ArrayList<>(interceptorRegistrations));
    }

    /**
     * 动态添加拦截器。
     * <p>
     * 新拦截器会被添加到链的末尾（在用户拦截器区域）。
     *
     * @param interceptor 要添加的拦截器
     */
    public void addInterceptor(ChatInterceptor interceptor) {
        interceptorRegistrations.add(ChatInterceptorRegistration.of(interceptor));
    }

    public void addInterceptor(int index, ChatInterceptor interceptor) {
        interceptorRegistrations.add(index, ChatInterceptorRegistration.of(interceptor));
    }

    public void addInterceptorRegistration(ChatInterceptorRegistration registration) {
        if (registration == null) {
            throw new IllegalArgumentException("ChatInterceptorRegistration must not be null");
        }
        interceptorRegistrations.add(registration);
    }

    public void addInterceptorRegistration(int index, ChatInterceptorRegistration registration) {
        if (registration == null) {
            throw new IllegalArgumentException("ChatInterceptorRegistration must not be null");
        }
        interceptorRegistrations.add(index, registration);
    }
}
