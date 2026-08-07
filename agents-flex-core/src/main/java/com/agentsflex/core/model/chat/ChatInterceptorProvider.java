/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentsflex.core.model.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 为当前 ChatModel 请求提供拦截器的可选能力。
 *
 * <p>接口本身不限定实现类型。当前 BaseChatModel 会从 Prompt 自身、Prompt 显式配置的
 * Provider、Prompt 中的 Tool 以及 ToolGroup 发现本能力，并且只把 Registration 加入当前请求的
 * 责任链，不会修改自身的全局或实例级拦截器配置。</p>
 */
public interface ChatInterceptorProvider {

    /**
     * 返回当前组件需要参与 ChatModel 请求的完整注册信息。
     *
     * <p>需要指定名称、执行顺序或条件匹配时覆盖本方法。默认实现会把
     * {@link #getChatInterceptors()} 返回的简单拦截器转换为默认 Registration。</p>
     *
     * @return Registration 列表；不得返回 {@code null} 或包含 {@code null}
     */
    default List<ChatInterceptorRegistration> getChatInterceptorRegistrations() {
        List<ChatInterceptor> interceptors = getChatInterceptors();
        if (interceptors == null) {
            throw new IllegalStateException("ChatInterceptorProvider#getChatInterceptors() must not return null: "
                + getClass().getName());
        }
        if (interceptors.isEmpty()) {
            return Collections.emptyList();
        }
        List<ChatInterceptorRegistration> registrations = new ArrayList<>(interceptors.size());
        for (ChatInterceptor interceptor : interceptors) {
            if (interceptor == null) {
                throw new IllegalStateException("ChatInterceptorProvider returned a null interceptor: "
                    + getClass().getName());
            }
            registrations.add(ChatInterceptorRegistration.of(interceptor));
        }
        return registrations;
    }

    /**
     * 返回不需要自定义注册属性的简单拦截器。
     *
     * <p>这是兼容和便捷入口；需要 order、matcher 或稳定名称时，应覆盖
     * {@link #getChatInterceptorRegistrations()}。</p>
     *
     * @return 拦截器列表；默认返回空列表
     */
    default List<ChatInterceptor> getChatInterceptors() {
        return Collections.emptyList();
    }
}
