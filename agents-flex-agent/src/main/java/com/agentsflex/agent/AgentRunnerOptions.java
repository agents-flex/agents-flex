/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * AgentRunner 的事件和执行基础设施配置。
 */
public final class AgentRunnerOptions {
    /**
     * 模型和工具调用使用的共享 daemon 执行器。daemon 线程不会阻止应用正常退出。
     */
    private static final Executor DEFAULT_ASYNC_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "agent-execution");
        thread.setDaemon(true);
        return thread;
    });
    private final Executor eventExecutor;
    private final Executor toolExecutor;
    private final Executor modelExecutor;

    private AgentRunnerOptions(Builder builder) {
        this.eventExecutor = builder.eventExecutor == null ? Runnable::run : builder.eventExecutor;
        this.toolExecutor = builder.toolExecutor == null ? DEFAULT_ASYNC_EXECUTOR : builder.toolExecutor;
        this.modelExecutor = builder.modelExecutor == null ? DEFAULT_ASYNC_EXECUTOR : builder.modelExecutor;
    }

    /**
     * 创建配置构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回事件同步、模型/工具使用共享 daemon 执行器的默认配置。
     */
    public static AgentRunnerOptions defaults() {
        return builder().build();
    }

    /**
     * @return 事件监听器分发执行器，默认在发布线程同步执行
     */
    public Executor getEventExecutor() {
        return eventExecutor;
    }

    /**
     * @return 本地工具执行器；配置超时时由 Runner 用于 FutureTask
     */
    public Executor getToolExecutor() {
        return toolExecutor;
    }

    /**
     * @return 模型调用执行器；配置超时时由 Runner 用于 FutureTask
     */
    public Executor getModelExecutor() {
        return modelExecutor;
    }

    public static final class Builder {
        private Executor eventExecutor;
        private Executor toolExecutor;
        private Executor modelExecutor;

        /**
         * 设置事件监听器分发执行器；为空时使用同步执行。
         */
        public Builder eventExecutor(Executor value) {
            eventExecutor = value;
            return this;
        }

        /**
         * 设置本地工具执行器；建议使用具备隔离和取消能力的线程池。
         */
        public Builder toolExecutor(Executor value) {
            toolExecutor = value;
            return this;
        }

        /**
         * 设置模型调用执行器；配置超时时不要使用会在调用线程直接执行的 Executor。
         */
        public Builder modelExecutor(Executor value) {
            modelExecutor = value;
            return this;
        }

        public AgentRunnerOptions build() {
            return new AgentRunnerOptions(this);
        }
    }
}
