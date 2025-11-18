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

package com.agentsflex.core.model.chat;

import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.Prompt;

/**
 * 聊天模型请求拦截器接口。
 * <p>
 * 该接口允许开发者在 LLM 请求的各个生命周期阶段插入自定义逻辑，包括：
 * <ul>
 *   <li>请求前修改输入（Prompt 和 ChatOptions）</li>
 *   <li>响应后修改或观察输出（AiMessageResponse）</li>
 *   <li>统一处理请求完成（无论成功或失败）</li>
 *   <li>包装流式响应监听器，实现对流式消息的拦截或转换</li>
 * </ul>
 * <p>
 * 拦截器可通过 {@link GlobalChatInterceptors} 注册为全局拦截器，
 * 或在创建 {@link BaseChatModel} 实例时作为局部拦截器传入。
 * <p>
 * 所有方法均提供默认空实现，子类只需重写关心的方法。
 */
public interface ChatInterceptor {

    /**
     * 请求前拦截方法。
     * <p>
     * 在实际调用 LLM 之前执行，允许拦截器修改传入的 {@link Prompt} 或 {@link ChatOptions}。
     * 注意：本方法应返回新的对象实例（不可变风格），而非修改原始对象。
     * <p>
     * 典型用途：
     * <ul>
     *   <li>自动追加系统提示（System Message）</li>
     *   <li>动态设置超时、温度等参数</li>
     *   <li>敏感词预检测或脱敏</li>
     * </ul>
     *
     * @param prompt    原始用户输入的 Prompt
     * @param options   原始聊天选项（如超时、流式开关等）
     * @param chatModel 当前 ChatModel 实例
     * @return 包含修改后 Prompt 和/或 Options 的结果对象；若不修改，返回 {@code new PreHandleResult(prompt, options)}
     */
    default PreHandleResult preHandle(Prompt prompt, ChatOptions options, ChatModel chatModel) {
        return null;
    }

    /**
     * 响应后拦截方法。
     * <p>
     * 在 LLM 返回响应后、返回给调用方之前执行。
     * 允许拦截器修改或观察最终的 {@link AiMessageResponse}。
     * <p>
     * {@code success} 参数明确指示本次调用是否成功（无异常且非业务错误），
     * 便于拦截器区分处理成功响应与错误响应。
     * <p>
     * 典型用途：
     * <ul>
     *   <li>对响应内容进行后处理（如格式化、翻译）</li>
     *   <li>记录成功响应的 token 使用量</li>
     *   <li>对错误响应进行统一包装或日志记录</li>
     * </ul>
     *
     * @param originalPrompt  最初传入的 Prompt（未被修改前的原始输入）
     * @param originalOptions 最初传入的 ChatOptions
     * @param response        LLM 返回的响应对象（可能包含错误）
     * @param success         {@code true} 表示调用成功（无异常且 response.isError() == false），否则为 {@code false}
     * @return 修改后的响应对象；若不修改，应返回 {@code response}
     */
    default AiMessageResponse postHandle(
        Prompt originalPrompt,
        ChatOptions originalOptions,
        AiMessageResponse response,
        boolean success) {
        return response;
    }

    /**
     * 请求完成后的统一回调方法。
     * <p>
     * 无论 LLM 调用成功或失败（包括抛出异常），此方法都会被调用。
     * 适用于需要统一清理资源、记录审计日志、上报指标等场景。
     * <p>
     * 注意：此方法在 {@link #postHandle} 之后执行（若调用成功），
     * 或在异常被捕获后执行（若调用失败）。
     *
     * @param prompt   实际用于调用 LLM 的 Prompt（可能已被 preHandle 修改）
     * @param options  实际用于调用 LLM 的 ChatOptions
     * @param response 若调用成功，为非 null 的响应对象；若失败，可能为 null 或包含错误信息
     * @param ex       若调用过程中抛出异常，此参数非 null；否则为 null
     */
    default void afterCompletion(
        Prompt prompt,
        ChatOptions options,
        AiMessageResponse response,
        Throwable ex) {
        // 默认空实现，子类可选择性重写
    }


    /**
     * {@code preHandle} 方法的返回值载体类。
     * <p>
     * 用于同时携带修改后的 {@link Prompt} 和 {@link ChatOptions}。
     * 设计为不可变类，确保线程安全。
     */
    final class PreHandleResult {

        private final Prompt prompt;
        private final ChatOptions options;

        /**
         * 构造一个 PreHandleResult 实例。
         *
         * @param prompt  修改后的 Prompt（若未修改，应传入原始 prompt）
         * @param options 修改后的 ChatOptions（若未修改，应传入原始 options）
         */
        public PreHandleResult(Prompt prompt, ChatOptions options) {
            this.prompt = prompt;
            this.options = options;
        }

        /**
         * 获取修改后的 Prompt。
         *
         * @return Prompt 实例
         */
        public Prompt getPrompt() {
            return prompt;
        }

        /**
         * 获取修改后的 ChatOptions。
         *
         * @return ChatOptions 实例
         */
        public ChatOptions getOptions() {
            return options;
        }
    }
}
