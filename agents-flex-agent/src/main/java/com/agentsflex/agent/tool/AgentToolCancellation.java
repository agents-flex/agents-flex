/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次 Tool 调用的本地停止控制器。
 *
 * <p>控制器只在当前 Tool 调用期间有效，不保存到 Snapshot，也不跨 JVM 传播。Tool 可以通过
 * {@link #onStop(Runnable)} 注册关闭子进程、取消 HTTP 请求或释放其他外部资源的轻量回调；Runner
 * 收到 {@code stop} 后会先触发这些回调，再中断承载 Tool 的 Java 线程。</p>
 *
 * <p>回调只应发送停止信号，不应执行长时间等待。Tool 仍需在自己的 {@code finally} 中等待并确认
 * 资源退出。所有回调都只触发一次，Tool 正常返回、抛异常或被取消后，未触发的回调会自动清理。</p>
 */
public final class AgentToolCancellation implements AutoCloseable {

    private final Object monitor = new Object();
    private final List<Runnable> handlers = new ArrayList<>();
    private boolean requested;
    private boolean closed;

    /**
     * @return 当前调用是否已经收到本地停止请求
     */
    public boolean isRequested() {
        synchronized (monitor) {
            return requested;
        }
    }

    /**
     * 如果已收到停止请求或当前线程已被中断，则立即抛出异常结束 Tool。
     */
    public void throwIfRequested() {
        if (Thread.currentThread().isInterrupted() || isRequested()) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool stop requested");
        }
    }

    /**
     * 注册一次性停止回调。
     *
     * <p>如果停止已经发生，回调会在本方法返回前立即执行；如果 Tool 已经结束，回调不会再执行。
     * 返回的句柄用于在资源提前释放时主动注销回调，Runner 在 Tool 结束时还会统一兜底清理。</p>
     *
     * @param handler 收到停止请求时执行的轻量、幂等动作
     * @return 可重复关闭的回调注册句柄
     */
    public Registration onStop(Runnable handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        boolean invokeImmediately;
        synchronized (monitor) {
            if (closed) {
                return new Registration(null);
            }
            invokeImmediately = requested;
            if (!invokeImmediately) {
                handlers.add(handler);
            }
        }
        if (invokeImmediately) invokeSafely(handler);
        if (invokeImmediately) return new Registration(null);
        return new Registration(handler);
    }

    /**
     * 触发本地停止通知。
     *
     * <p>通常由 AgentRunner 调用；业务代码也可以用它主动结束当前 Tool 的资源操作。该方法
     * 只触发本地回调，不会修改持久化 Turn 状态。</p>
     */
    public void request() {
        List<Runnable> pending;
        synchronized (monitor) {
            if (closed || requested) return;
            requested = true;
            pending = new ArrayList<>(handlers);
            handlers.clear();
        }
        for (Runnable handler : pending) invokeSafely(handler);
    }

    /**
     * 结束本次 Tool 调用并清空尚未触发的回调。
     */
    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            handlers.clear();
        }
    }

    /**
     * 停止回调属于清理动作，不能因为业务回调异常而阻断 Runner 的停止流程。
     */
    private void invokeSafely(Runnable handler) {
        try {
            handler.run();
        } catch (RuntimeException ignored) {
            // 回调失败不影响持久化取消和 Tool 线程中断。
        }
    }

    /**
     * 停止回调的注销句柄。关闭操作幂等且不声明受检异常，便于 Tool 在 finally 中直接使用。
     */
    public final class Registration implements AutoCloseable {
        private final Runnable handler;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Registration(Runnable handler) {
            this.handler = handler;
        }

        @Override
        public void close() {
            if (handler == null || !closed.compareAndSet(false, true)) return;
            synchronized (monitor) {
                handlers.remove(handler);
            }
        }
    }
}
