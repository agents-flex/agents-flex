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
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.client.ChatClient;
import com.agentsflex.core.model.client.OpenAIChatClient;
import com.agentsflex.core.prompt.Prompt;

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
public class OpenAIChatModel extends BaseChatModel<OpenAIChatConfig> {

    /**
     * 构造 OpenAI 聊天模型实例。
     * <p>
     *
     * @param config OpenAI 聊天配置
     */
    public OpenAIChatModel(OpenAIChatConfig config) {
        super(config);
    }

    /**
     * 工厂方法：通过 API Key 创建实例。
     *
     * @param apiKey OpenAI API Key
     * @return OpenAI 聊天模型实例
     */
    public static OpenAIChatModel of(String apiKey) {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setApiKey(apiKey);
        return new OpenAIChatModel(config);
    }

    /**
     * 工厂方法：通过 API Key 和自定义 Endpoint 创建实例。
     *
     * @param apiKey   OpenAI API Key
     * @param endpoint 自定义 API Endpoint（如 Azure OpenAI）
     * @return OpenAI 聊天模型实例
     */
    public static OpenAIChatModel of(String apiKey, String endpoint) {
        OpenAIChatConfig config = new OpenAIChatConfig();
        config.setApiKey(apiKey);
        config.setEndpoint(endpoint);
        return new OpenAIChatModel(config);
    }

    /**
     * 构建 OpenAI 请求体。
     * <p>
     * 使用 {@link OpenAILlmUtil} 将 Prompt 转换为 OpenAI API 格式的 JSON。
     *
     * @param prompt    用户提示
     * @param options   聊天选项
     * @param streaming 是否为流式请求
     * @return OpenAI API 格式的 JSON 请求体
     */
    @Override
    protected String buildRequestBody(Prompt prompt, ChatOptions options, boolean streaming) {
        return OpenAILlmUtil.promptToPayload(prompt, getConfig(), options, streaming);
    }


    /**
     * 创建 OpenAI 协议客户端。
     * <p>
     * 根据上下文中的流式标志选择合适的解析器，
     * 并传递所有必要参数给 {@link OpenAIChatClient}。
     *
     * @param context 聊天上下文（可能已被拦截器修改）
     * @return OpenAI 协议客户端
     */
    @Override
    public ChatClient buildClient(ChatContext context) {
        return new OpenAIChatClient(
            this,
            context
        );
    }


}
