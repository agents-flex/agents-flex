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

import com.agentsflex.core.model.chat.BaseChatConfig;

/**
 * OpenAI Responses 对话模型配置。
 * <p>
 * 默认使用 {@code https://api.openai.com/v1/responses} 和 {@code gpt-4.1}，
 * 支持文本对话及本地函数工具。历史消息由调用方管理，不依赖服务端会话。
 * API Key、模型名称等通用属性通过 {@link BaseChatConfig} 的访问方法配置。
 */
public class OpenAIResponsesChatConfig extends BaseChatConfig {

    /**
     * 创建使用默认 Endpoint、模型和工具能力的配置。
     */
    public OpenAIResponsesChatConfig() {
        setProvider("openai");
        setEndpoint("https://api.openai.com");
        setRequestPath("/v1/responses");
        setModel("gpt-4.1");
        setSupportTool(true);
        setSupportToolMessage(true);
    }

    /**
     * 使用当前配置创建 Responses 对话模型。
     *
     * @return 新的对话模型实例
     */
    public OpenAIResponsesChatModel toChatModel() {
        return new OpenAIResponsesChatModel(this);
    }
}
