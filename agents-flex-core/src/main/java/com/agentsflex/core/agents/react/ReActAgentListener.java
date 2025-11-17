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
package com.agentsflex.core.agents.react;

import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.functions.Function;
import com.agentsflex.core.model.chat.response.AiMessageResponse;

import java.util.List;

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
     * 当解析步骤时发生错误时触发
     *
     * @param content 错误内容
     */
    default void onStepParseError(String content) {

    }

    /**
     * 当未匹配到任何工具时触发
     *
     * @param step      当前步骤
     * @param functions 可用的工具列表
     */
    default void onActionNotMatched(ReActStep step, List<Function> functions) {

    }

    /**
     * 当工具执行错误时触发
     *
     * @param e 错误对象
     */
    default void onActionInvokeError(Exception e) {

    }

    /**
     * 当工具返回的 JSON 格式错误时触发
     *
     * @param step  当前步骤
     * @param error 错误对象
     */
    default void onActionJsonParserError(ReActStep step, Exception error) {

    }

    /**
     * 当发生异常时触发
     *
     * @param error 异常对象
     */
    default void onError(Exception error) {
    }


}
