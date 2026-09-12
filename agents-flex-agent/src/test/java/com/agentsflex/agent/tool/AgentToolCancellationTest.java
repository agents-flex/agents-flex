/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.tool;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * Tool 停止通知的触发、幂等和作用域清理测试。
 */
public class AgentToolCancellationTest {

    @Test
    public void shouldInvokeEachStopHandlerOnlyOnce() throws Exception {
        AgentToolCancellation cancellation = new AgentToolCancellation();
        AtomicInteger callbacks = new AtomicInteger();
        cancellation.onStop(callbacks::incrementAndGet);

        cancellation.request();
        cancellation.request();
        assertEquals(1, callbacks.get());

        // 停止已经发生后再注册，必须立即执行一次，不能遗漏竞态窗口。
        cancellation.onStop(callbacks::incrementAndGet);
        assertEquals(2, callbacks.get());
        cancellation.close();
    }

    @Test
    public void shouldRemoveClosedHandlerAndClearScope() throws Exception {
        AgentToolCancellation cancellation = new AgentToolCancellation();
        AtomicInteger callbacks = new AtomicInteger();
        AutoCloseable registration = cancellation.onStop(callbacks::incrementAndGet);
        registration.close();
        cancellation.request();
        assertEquals(0, callbacks.get());

        cancellation.onStop(callbacks::incrementAndGet);
        cancellation.close();
        cancellation.request();
        assertEquals(1, callbacks.get());
    }
}
