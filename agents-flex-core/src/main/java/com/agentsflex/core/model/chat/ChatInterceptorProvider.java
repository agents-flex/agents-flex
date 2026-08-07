/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentsflex.core.model.chat;

import java.util.List;

/**
 * 为当前 ChatModel 请求提供拦截器的可选能力。
 *
 * <p>接口本身不限定实现类型，可以由 Tool、ToolGroup 或其他请求扩展组件实现。当前
 * BaseChatModel 会自动发现 Prompt 中实现本接口的 Tool，并且只把其拦截器加入当前请求的
 * 责任链，不会修改自身的全局或实例级拦截器配置。</p>
 */
public interface ChatInterceptorProvider {

    /**
     * 返回当前组件需要参与 ChatModel 请求的拦截器。
     *
     * @return 拦截器列表；不得返回 {@code null}，没有拦截器时返回空列表
     */
    List<ChatInterceptor> getChatInterceptors();
}
