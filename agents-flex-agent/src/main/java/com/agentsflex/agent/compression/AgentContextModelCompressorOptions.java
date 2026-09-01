/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.compression;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.model.chat.ChatOptions;

import java.util.function.Function;

/**
 * 基于 ChatModel 的上下文压缩器配置。
 *
 * <p>配置对象不可变，ChatOptions 会在构建和读取时复制，因此可以被并发压缩请求复用。</p>
 */
public final class AgentContextModelCompressorOptions {

    private final String instruction;
    private final String historyHeader;
    private final String perMessageRequest;
    private final String summaryPrefix;
    private final ChatOptions chatOptions;
    /**
     * 摘要模型单次调用超时；0 表示不限制。
     */
    private final long modelCallTimeoutMillis;
    private final Function<Message, String> modelMessageFormatter;
    private final Function<Message, String> perMessageFormatter;

    private AgentContextModelCompressorOptions(Builder builder) {
        if (builder.instruction == null || builder.instruction.trim().isEmpty()) {
            throw new IllegalArgumentException("instruction must not be blank");
        }
        if (builder.historyHeader == null) {
            throw new IllegalArgumentException("historyHeader must not be null");
        }
        if (builder.perMessageRequest == null) {
            throw new IllegalArgumentException("perMessageRequest must not be null");
        }
        if (builder.summaryPrefix == null) {
            throw new IllegalArgumentException("summaryPrefix must not be null");
        }
        if (builder.modelMessageFormatter == null || builder.perMessageFormatter == null) {
            throw new IllegalArgumentException("message formatters must not be null");
        }
        this.instruction = builder.instruction;
        this.historyHeader = builder.historyHeader;
        this.perMessageRequest = builder.perMessageRequest;
        this.summaryPrefix = builder.summaryPrefix;
        this.chatOptions = builder.chatOptions == null
            ? new ChatOptions() : builder.chatOptions.copy();
        if (builder.modelCallTimeoutMillis < 0) {
            throw new IllegalArgumentException("modelCallTimeoutMillis must not be negative");
        }
        this.modelCallTimeoutMillis = builder.modelCallTimeoutMillis;
        this.modelMessageFormatter = builder.modelMessageFormatter;
        this.perMessageFormatter = builder.perMessageFormatter;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getInstruction() {
        return instruction;
    }

    public String getHistoryHeader() {
        return historyHeader;
    }

    public String getPerMessageRequest() {
        return perMessageRequest;
    }

    public String getSummaryPrefix() {
        return summaryPrefix;
    }

    /**
     * 返回请求级副本，避免不同压缩调用共享可变 ChatOptions。
     */
    public ChatOptions getChatOptions() {
        return chatOptions.copy();
    }

    /**
     * @return 摘要模型单次调用超时时间（毫秒），0 表示不限制
     */
    public long getModelCallTimeoutMillis() {
        return modelCallTimeoutMillis;
    }

    public Function<Message, String> getModelMessageFormatter() {
        return modelMessageFormatter;
    }

    public Function<Message, String> getPerMessageFormatter() {
        return perMessageFormatter;
    }

    public static final class Builder {
        private String instruction;
        private String historyHeader = "\n\n历史消息：\n";
        private String perMessageRequest =
            "\n请逐条摘要以下消息，并仅返回 JSON 数组，每项包含 messageId 和 summary：\n";
        private String summaryPrefix = "以下是较早对话的摘要，请将其作为历史事实参考：";
        private ChatOptions chatOptions;
        private long modelCallTimeoutMillis;
        private Function<Message, String> modelMessageFormatter = message -> {
            String text = message.getTextContent();
            return text == null || text.isEmpty()
                ? "" : message.getClass().getSimpleName() + ": " + text + '\n';
        };
        private Function<Message, String> perMessageFormatter = message ->
            "messageId=" + message.getMessageId()
                + ", role=" + message.getClass().getSimpleName()
                + ", content=" + message.getTextContent() + '\n';

        public Builder instruction(String value) {
            this.instruction = value;
            return this;
        }

        public Builder historyHeader(String value) {
            this.historyHeader = value;
            return this;
        }

        public Builder perMessageRequest(String value) {
            this.perMessageRequest = value;
            return this;
        }

        public Builder summaryPrefix(String value) {
            this.summaryPrefix = value;
            return this;
        }

        public Builder chatOptions(ChatOptions value) {
            this.chatOptions = value;
            return this;
        }

        /**
         * 设置摘要模型单次调用超时；超时后压缩器抛出异常，由压缩失败策略决定后续行为。
         */
        public Builder modelCallTimeoutMillis(long value) {
            this.modelCallTimeoutMillis = value;
            return this;
        }

        public Builder modelMessageFormatter(Function<Message, String> value) {
            this.modelMessageFormatter = value;
            return this;
        }

        /**
         * 设置逐条摘要输入格式。返回文本必须包含当前消息的 messageId，供摘要结果回填。
         */
        public Builder perMessageFormatter(Function<Message, String> value) {
            this.perMessageFormatter = value;
            return this;
        }

        public AgentContextModelCompressorOptions build() {
            return new AgentContextModelCompressorOptions(this);
        }
    }
}
