/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理当前 JVM 内可以被立即停止的模型或工具执行句柄。
 *
 * <p>该注册表只负责进程内的“尽快停止”。任务是否已经请求取消、是否最终进入
 * {@code CANCELLED}，仍然以 {@link com.agentsflex.agent.store.AgentTurnStore} 中的持久化状态为准。</p>
 */
final class AgentExecutionRegistry {

    private final ConcurrentHashMap<String, ExecutionGroup> groups = new ConcurrentHashMap<>();

    Registration register(String turnId, Runnable stopAction) {
        if (turnId == null || stopAction == null) {
            throw new IllegalArgumentException("turnId and stopAction must not be null");
        }
        ExecutionGroup group = groups.computeIfAbsent(turnId, ExecutionGroup::new);
        Registration registration = new Registration(group, stopAction);
        synchronized (group.monitor) {
            group.registrations.add(registration);
        }
        // stop() 可能先于模型线程登记句柄到达；登记后必须立即补发停止动作，不能让竞态窗口漏掉取消。
        if (group.stopRequested.get()) registration.stop();
        return registration;
    }

    boolean stop(String turnId) {
        if (turnId == null) return false;
        ExecutionGroup group = groups.computeIfAbsent(turnId, ExecutionGroup::new);
        group.stopRequested.set(true);
        Registration[] registrations;
        synchronized (group.monitor) {
            registrations = group.registrations.toArray(new Registration[0]);
        }
        for (Registration registration : registrations) registration.stop();
        return registrations.length > 0;
    }

    boolean awaitIdle(String turnId, long timeoutMillis) throws InterruptedException {
        ExecutionGroup group = groups.get(turnId);
        if (group == null) return true;
        long deadline = System.nanoTime() + Math.max(0L, timeoutMillis) * 1_000_000L;
        synchronized (group.monitor) {
            while (!group.registrations.isEmpty()) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) return false;
                long millis = Math.max(1L, Math.min(10L, remainingNanos / 1_000_000L));
                group.monitor.wait(millis);
            }
            return true;
        }
    }

    boolean isIdle(String turnId) {
        ExecutionGroup group = groups.get(turnId);
        if (group == null) return true;
        synchronized (group.monitor) {
            return group.registrations.isEmpty();
        }
    }

    void clear(String turnId) {
        if (turnId == null) return;
        ExecutionGroup group = groups.get(turnId);
        if (group == null) return;
        synchronized (group.monitor) {
            // Turn 已经终态但底层调用可能仍未退出，先标记待清理，避免 stopAndWait 看不到句柄。
            if (group.registrations.isEmpty()) {
                groups.remove(turnId, group);
            } else {
                group.clearRequested = true;
            }
        }
    }

    /**
     * 能够区分“Future 已被取消”和“实际调用已经退出”的任务包装器。
     *
     * <p>{@link FutureTask#cancel(boolean)} 会立即把 Future 标记为完成，即使底层调用忽略中断仍在
     * 执行。如果直接在等待线程的 finally 中释放注册句柄，{@code stopAndWait} 就会提前返回。该包装器
     * 让正常路径在 Callable 的 finally 中释放句柄；若任务尚未开始就被取消，则立即释放句柄。</p>
     */
    static final class TrackedFutureTask<V> extends FutureTask<V> {
        private final Runnable closeRegistration;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean closePending = new AtomicBoolean(false);

        TrackedFutureTask(Callable<V> callable, Runnable closeRegistration) {
            super(callable);
            if (closeRegistration == null) {
                throw new IllegalArgumentException("closeRegistration must not be null");
            }
            this.closeRegistration = closeRegistration;
        }

        @Override
        public void run() {
            started.set(true);
            try {
                super.run();
            } finally {
                closeRegistration.run();
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled && !started.get()) {
                closePending.set(true);
                closeRegistration.run();
            }
            return cancelled;
        }

        /**
         * 绑定注册句柄，并补发任务尚未启动时竞态窗口内遗漏的关闭动作。
         */
        void registrationBound() {
            if (closePending.get()) closeRegistration.run();
        }
    }

    final class Registration implements AutoCloseable {
        private final ExecutionGroup group;
        private final Runnable stopAction;
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Registration(ExecutionGroup group, Runnable stopAction) {
            this.group = group;
            this.stopAction = stopAction;
        }

        void stop() {
            if (stopped.compareAndSet(false, true)) {
                try {
                    stopAction.run();
                } catch (RuntimeException ignored) {
                    // 底层客户端无法停止时，持久化取消标记仍然是最终事实来源。
                }
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (group.monitor) {
                group.registrations.remove(this);
                group.monitor.notifyAll();
                if (group.registrations.isEmpty() && group.clearRequested) {
                    groups.remove(group.turnId, group);
                }
            }
        }
    }

    private static final class ExecutionGroup {
        private final String turnId;
        private final Set<Registration> registrations = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final Object monitor = new Object();
        private boolean clearRequested;

        private ExecutionGroup(String turnId) {
            this.turnId = turnId;
        }
    }
}
