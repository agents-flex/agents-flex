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
package com.agentsflex.core.model.client;

import com.agentsflex.core.model.chat.BaseChatConfig;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.prompt.Prompt;

public interface ChatRequestSpecBuilder {

    /**
     * Builds transport settings before the interceptor chain starts.
     */
    ChatRequestSpec buildRequestSpec(Prompt prompt, ChatOptions options, BaseChatConfig config);

    /**
     * Builds the request body from the final context after all interceptors have proceeded.
     */
    default String buildRequestBody(Prompt prompt, ChatOptions options, BaseChatConfig config) {
        throw new UnsupportedOperationException(
            "ChatRequestSpecBuilder must implement buildRequestBody with streaming state");
    }

    /**
     * 构建请求体时由 ChatModel 明确传入调用方式，避免把框架运行状态写入 ChatOptions。
     * 旧实现可继续覆盖三参数方法；新实现应覆盖此方法以正确处理流式协议。
     */
    default String buildRequestBody(Prompt prompt, ChatOptions options, BaseChatConfig config,
                                    boolean streaming) {
        return buildRequestBody(prompt, options, config);
    }
}
