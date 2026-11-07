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
package com.agentsflex.model.chat.openai.responses;

import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatInterceptor;

import java.util.List;

/**
 * OpenAI Responses 对话模型实现。
 * <p>
 * 负责组装 Responses 请求构建器和客户端，同步与流式调用共用
 * {@link BaseChatModel} 的拦截器、日志及上下文机制。
 * 使用本类时显式选择 Responses 协议，现有 OpenAIChatModel 仍使用 Chat Completions。
 */
public class OpenAIResponsesChatModel extends BaseChatModel<OpenAIResponsesChatConfig> {

    /**
     * 构造模型，不配置实例级拦截器。
     *
     * @param config Responses 对话配置
     */
    public OpenAIResponsesChatModel(OpenAIResponsesChatConfig config) {
        this(config, null);
    }

    /**
     * 构造模型，并将实例级拦截器交给基础模型统一管理。
     *
     * @param config       Responses 对话配置
     * @param interceptors 实例级拦截器，可为 null 或空列表
     */
    public OpenAIResponsesChatModel(OpenAIResponsesChatConfig config, List<ChatInterceptor> interceptors) {
        super(config, interceptors);
        setChatRequestSpecBuilder(new ResponsesRequestSpecBuilder());
        setChatClient(new ResponsesChatClient(this));
    }
}
