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

import com.agentsflex.core.prompt.Prompt;

import java.util.Map;

/**
 * 聊天上下文管理器，用于在当前线程中保存聊天相关的上下文信息。
 * <p>
 * 典型用法：在 {@link BaseChatModel} 的 {@code doChat}/{@code doChatStream} 中设置上下文，
 * 供日志、监控、拦截器等模块使用。
 * <p>
 * 支持同步和流式调用，通过 {@link ChatContextScope} 实现自动清理。
 */
public final class ChatContextHolder {

    private static final ThreadLocal<ChatContext> CONTEXT_HOLDER = new ThreadLocal<>();

    private ChatContextHolder() {
        // 工具类，禁止实例化
    }


    /**
     * 开始一次聊天上下文，并设置传输层请求信息。
     * 适用于远程 LLM 模型（如 HTTP/gRPC/WebSocket）。
     *
     * @param config         聊天配置
     * @param options        聊天选项
     * @param prompt         用户提示
     * @param requestUrl     请求目标地址
     *                       <ul>
     *                         <li>HTTP: 完整 URL（如 "https://api.openai.com/v1/chat/completions"）</li>
     *                         <li>gRPC: 目标地址（如 "llm-service:50051"）</li>
     *                         <li>WebSocket: 连接 URI</li>
     *                         <li>本地模型: 模型文件路径</li>
     *                       </ul>
     * @param requestHeaders 传输层元数据
     *                       <ul>
     *                         <li>HTTP: 请求头（Headers）</li>
     *                         <li>gRPC: Metadata</li>
     *                         <li>WebSocket: 握手头或自定义属性</li>
     *                         <li>本地模型: 可为 null</li>
     *                       </ul>
     * @param requestBody    序列化后的请求体（通常是 JSON 字符串）
     * @return 可用于 try-with-resources 的作用域对象
     */
    public static ChatContextScope beginChat(
        ChatConfig config,
        ChatOptions options,
        Prompt prompt,
        String requestUrl,
        Map<String, String> requestHeaders,
        String requestBody) {
        ChatContext ctx = new ChatContext();
        ctx.config = config;
        ctx.options = options;
        ctx.prompt = prompt;
        ctx.requestUrl = requestUrl;
        ctx.requestHeaders = requestHeaders;
        ctx.requestBody = requestBody;
        CONTEXT_HOLDER.set(ctx);
        return new ChatContextScope(ctx);
    }

    /**
     * 获取当前线程的聊天上下文（可能为 null）。
     *
     * @return 聊天上下文，若未设置则返回 null
     */
    public static ChatContext currentContext() {
        return CONTEXT_HOLDER.get();
    }

    /**
     * 手动清除当前线程的上下文。
     * <p>
     * 通常由 {@link ChatContextScope} 自动调用，无需手动调用。
     */
    public static void clear() {
        CONTEXT_HOLDER.remove();
    }


    /**
     * 用于 try-with-resources 的作用域对象，确保上下文自动清理。
     */
    public static class ChatContextScope implements AutoCloseable {

        ChatContext context;

        public ChatContextScope(ChatContext context) {
            this.context = context;
        }

        @Override
        public void close() {
            clear();
        }
    }
}
