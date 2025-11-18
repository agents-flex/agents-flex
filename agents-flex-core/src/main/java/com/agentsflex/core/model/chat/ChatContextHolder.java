package com.agentsflex.core.model.chat;

import com.agentsflex.core.prompt.Prompt;
import io.opentelemetry.api.trace.Span;

/**
 * 聊天上下文管理器，用于在当前线程中保存聊天相关的上下文信息。
 * 典型用法：在 BaseChatModel 的 doChat/doChatStream 中设置上下文，
 * 供日志、监控、追踪等模块使用。
 */
public final class ChatContextHolder {

    private static final ThreadLocal<ChatContext> CONTEXT_HOLDER = new ThreadLocal<>();

    private ChatContextHolder() {
        // 工具类，禁止实例化
    }

    /**
     * 开始一次聊天上下文。返回 AutoCloseable 以便 try-with-resources 自动清理。
     */
    public static ChatContextScope beginChat(ChatConfig config, ChatOptions options, Prompt prompt, Span span) {
        ChatContext ctx = new ChatContext();
        ctx.config = config;
        ctx.options = options;
        ctx.prompt = prompt;
        ctx.span = span;
        CONTEXT_HOLDER.set(ctx);
        return new ChatContextScope();
    }

    /**
     * 获取当前线程的聊天上下文（可能为 null）。
     */
    public static ChatContext currentContext() {
        return CONTEXT_HOLDER.get();
    }

    /**
     * 手动清除当前线程的上下文（通常由 ChatContextScope 自动调用）。
     */
    public static void clear() {
        CONTEXT_HOLDER.remove();
    }

    /**
     * 聊天上下文数据容器。
     */
    public static class ChatContext {
        private ChatConfig config;
        private ChatOptions options;
        private Prompt prompt;
        private Span span;

        public ChatConfig getConfig() {
            return config;
        }

        public ChatOptions getOptions() {
            return options;
        }

        public Prompt getPrompt() {
            return prompt;
        }

        public Span getSpan() {
            return span;
        }
    }

    /**
     * 用于 try-with-resources 的作用域对象，确保上下文自动清理。
     */
    public static class ChatContextScope implements AutoCloseable {
        @Override
        public void close() {
            clear();
        }
    }
}
