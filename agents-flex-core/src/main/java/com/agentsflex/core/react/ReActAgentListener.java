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
package com.agentsflex.core.react;

import com.agentsflex.core.llm.ChatContext;
import com.agentsflex.core.llm.response.AiMessageResponse;

/**
 * ReActAgent 的监听器接口，用于监听执行过程中的关键事件。
 */
public interface ReActAgentListener {

    /**
     * 当 LLM 生成响应时触发
     *
     * @param response 原始响应内容
     */
    default void onChatResponse(AiMessageResponse response) {
    }

    /**
     * 当 LLM 生成响应时触发
     *
     * @param context  上下文信息
     * @param response 原始响应内容
     */
    default void onChatResponseStream(ChatContext context, AiMessageResponse response) {
    }


    /**
     * 当未命中工具时触发
     *
     * @param response 原始响应内容
     */
    default void onNonActionResponse(AiMessageResponse response) {
    }

    /**
     * 当未命中工具时触发
     */
    default void onNonActionResponseStream(ChatContext context) {
    }


    /**
     * 当检测到最终答案时触发
     *
     * @param finalAnswer 最终答案内容
     */
    default void onFinalAnswer(String finalAnswer) {
    }

    /**
     * 当调用工具前触发
     *
     * @param step 当前步骤
     */
    default void onActionStart(ReActStep step) {
    }

    /**
     * 当调用工具完成后触发
     *
     * @param step   工具名称
     * @param result 工具返回结果
     */
    default void onActionEnd(ReActStep step, Object result) {
    }

    /**
     * 当达到最大迭代次数仍未获得答案时触发
     */
    default void onMaxIterationsReached() {
    }

    /**
     * 当发生异常时触发
     *
     * @param error 异常对象
     */
    default void onError(Exception error) {
    }

}
