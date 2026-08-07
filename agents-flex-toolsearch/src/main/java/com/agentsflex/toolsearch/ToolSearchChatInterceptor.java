/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatInterceptor;
import com.agentsflex.core.model.chat.StreamChain;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.SyncChain;
import com.agentsflex.core.model.chat.response.AiMessageResponse;

/**
 * 普通 ChatModel 场景的 ToolSearch 请求拦截器。
 *
 * <p>应用只需把无状态的 ToolSearchTool 加入 Prompt，并给 ChatModel 注册本拦截器。每次调用模型前，
 * 拦截器从消息链读取最近一次搜索结果，创建只对本次请求生效的 Prompt 快照，不修改原始 Prompt。</p>
 */
public final class ToolSearchChatInterceptor implements ChatInterceptor {

    @Override
    public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context,
                                       SyncChain chain) {
        context.setPrompt(ToolSearchPromptResolver.resolve(context.getPrompt()));
        return chain.proceed(chatModel, context);
    }

    @Override
    public void interceptStream(BaseChatModel<?> chatModel, ChatContext context,
                                StreamResponseListener listener, StreamChain chain) {
        context.setPrompt(ToolSearchPromptResolver.resolve(context.getPrompt()));
        chain.proceed(chatModel, context, listener);
    }
}
