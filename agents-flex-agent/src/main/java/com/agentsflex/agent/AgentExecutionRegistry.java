/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local execution handles used by the immediate stop API.
 */
final class AgentExecutionRegistry {

    private final ConcurrentHashMap<String, ExecutionGroup> groups = new ConcurrentHashMap<>();

    Registration register(String turnId, Runnable stopAction) {
        if (turnId == null || stopAction == null) {
            throw new IllegalArgumentException("turnId and stopAction must not be null");
        }
        ExecutionGroup group = groups.computeIfAbsent(turnId, key -> new ExecutionGroup());
        Registration registration = new Registration(group, stopAction);
        group.registrations.add(registration);
        if (group.stopRequested.get()) registration.stop();
        return registration;
    }

    boolean stop(String turnId) {
        if (turnId == null) return false;
        ExecutionGroup group = groups.computeIfAbsent(turnId, key -> new ExecutionGroup());
        group.stopRequested.set(true);
        boolean found = false;
        for (Registration registration : group.registrations) {
            found = true;
            registration.stop();
        }
        if (!found) group.idle.countDown();
        return found;
    }

    boolean awaitIdle(String turnId, long timeoutMillis) throws InterruptedException {
        ExecutionGroup group = groups.get(turnId);
        return group == null || group.idle.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    boolean isIdle(String turnId) {
        ExecutionGroup group = groups.get(turnId);
        return group == null || group.registrations.isEmpty();
    }

    void clear(String turnId) {
        if (turnId != null) groups.remove(turnId);
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
                    // The durable cancellation flag is authoritative if a client cannot be stopped.
                }
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            group.registrations.remove(this);
            if (group.registrations.isEmpty()) group.idle.countDown();
        }
    }

    private static final class ExecutionGroup {
        private final Set<Registration> registrations = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final CountDownLatch idle = new CountDownLatch(1);
    }
}
