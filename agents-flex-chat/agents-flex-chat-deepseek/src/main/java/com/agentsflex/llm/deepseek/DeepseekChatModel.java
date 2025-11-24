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
package com.agentsflex.llm.deepseek;

import com.agentsflex.core.model.chat.OpenAICompatibleChatModel;
import com.agentsflex.core.model.chat.interceptor.ChatInterceptor;
import com.agentsflex.core.model.chat.interceptor.GlobalChatInterceptors;

import java.util.List;

/**
 * @author huangjf
 * @version : v1.0
 */
public class DeepseekChatModel extends OpenAICompatibleChatModel<DeepseekConfig> {


    /**
     * 构造一个聊天模型实例，不使用实例级拦截器。
     *
     * @param config 聊天模型配置
     */
    public DeepseekChatModel(DeepseekConfig config) {
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
    public DeepseekChatModel(DeepseekConfig config, List<ChatInterceptor> userInterceptors) {
        super(config, userInterceptors);
    }
}
