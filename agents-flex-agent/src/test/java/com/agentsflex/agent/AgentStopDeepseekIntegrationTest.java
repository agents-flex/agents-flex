/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.model.chat.deepseek.DeepseekChatModel;
import com.agentsflex.model.chat.deepseek.DeepseekConfig;
import org.junit.Assume;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Real-provider verification for stopping an in-flight streaming model request.
 */
public class AgentStopDeepseekIntegrationTest {

    @Test
    public void shouldStopRealDeepseekStreamAfterFirstDelta() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assume.assumeTrue("DEEPSEEK_API_KEY is required for integration test",
            apiKey != null && !apiKey.trim().isEmpty());

        DeepseekConfig config = new DeepseekConfig();
        config.setApiKey(apiKey);
        DeepseekChatModel model = new DeepseekChatModel(config);
        Agent agent = Agent.builder("real-deepseek-stop")
            .instructions("输出一篇至少 3000 字的技术文章，尽量持续生成，不要提前总结。")
            .chatModel(model)
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        CountDownLatch firstDelta = new CountDownLatch(1);
        AtomicInteger deltas = new AtomicInteger();
        runner.addEventListener(event -> {
            if (event.getType() == AgentEventType.MODEL_TEXT_DELTA) {
                deltas.incrementAndGet();
                firstDelta.countDown();
            }
        });

        AgentTurn started = runner.start(agent, "开始写作，不要等待确认。",
            AgentTurnOptions.builder().streaming(true).build());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<AgentTurn> execution = executor.submit(() -> runner.run(started));
        try {
            assertTrue("DeepSeek did not emit a delta in time",
                firstDelta.await(30, TimeUnit.SECONDS));
            AgentTurn stopped = runner.stopAndWait(started.getId(), 15_000);
            AgentTurn completed = execution.get(30, TimeUnit.SECONDS);
            assertEquals(AgentTurnStatus.CANCELLED, stopped.getStatus());
            assertEquals(AgentTurnStatus.CANCELLED, completed.getStatus());
            assertTrue("expected at least one streamed delta", deltas.get() > 0);
        } finally {
            executor.shutdownNow();
        }
    }
}
