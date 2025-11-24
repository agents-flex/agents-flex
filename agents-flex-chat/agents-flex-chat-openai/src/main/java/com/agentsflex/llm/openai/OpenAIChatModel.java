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

import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.OpenAICompatibleChatModel;
import com.agentsflex.core.model.chat.interceptor.ChatInterceptor;
import com.agentsflex.core.model.chat.interceptor.GlobalChatInterceptors;

import java.util.List;

/**
 * OpenAI 聊天模型实现。
 * <p>
 * 该类封装了 OpenAI API 的具体调用细节，包括：
 * <ul>
 *   <li>请求体构建（支持同步/流式）</li>
 *   <li>HTTP 客户端管理</li>
 *   <li>解析器配置（同步/流式使用不同解析器）</li>
 * </ul>
 * <p>
 * 所有横切逻辑（监控、日志、拦截）由 {@link BaseChatModel} 的责任链处理，
 * 本类只关注 OpenAI 协议特有的实现细节。
 */
public class OpenAIChatModel extends OpenAICompatibleChatModel<OpenAIChatConfig> {


    /**
     * 构造一个聊天模型实例，不使用实例级拦截器。
     *
     * @param config 聊天模型配置
     */
    public OpenAIChatModel(OpenAIChatConfig config) {
        super(config);
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
    public OpenAIChatModel(OpenAIChatConfig config, List<ChatInterceptor> userInterceptors) {
        super(config, userInterceptors);
    }
}
