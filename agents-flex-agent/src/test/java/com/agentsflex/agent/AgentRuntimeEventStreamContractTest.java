/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentRuntimeEvent;
import com.agentsflex.agent.event.AgentRuntimeEventListener;
import com.agentsflex.agent.event.AgentRuntimeEventStream;
import com.agentsflex.agent.event.AgentRuntimeEventType;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.agent.AgentScenarioTestSupport.tool;
import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 实时事件流的顺序、隔离和监听器容错契约测试。 */
public class AgentRuntimeEventStreamContractTest {

    @Test
    public void shouldEmitCanonicalOrderForPlainModelResponse() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("done"));
        List<AgentRuntimeEvent> events = new ArrayList<>();
        AgentRunner runner = new AgentRunner().addRuntimeEventListener(events::add);

        AgentRun run = runner.run(Agent.builder("plain-events")
            .chatModel(model).build(), "hello");

        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertTypes(events,
            AgentRuntimeEventType.SNAPSHOT_SAVED,
            AgentRuntimeEventType.STEP_STARTED,
            AgentRuntimeEventType.RUN_STARTED,
            AgentRuntimeEventType.MODEL_STARTED,
            AgentRuntimeEventType.MODEL_COMPLETED,
            AgentRuntimeEventType.SNAPSHOT_SAVED,
            AgentRuntimeEventType.RUN_COMPLETED,
            AgentRuntimeEventType.STEP_COMPLETED);
        assertStrictSequence(events);
    }

    @Test
    public void shouldEmitToolCycleBeforeSecondModelCall() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("call-1", "lookup", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        List<AgentRuntimeEvent> events = new ArrayList<>();
        Agent agent = Agent.builder("tool-events")
            .chatModel(model)
            .tool(tool("lookup", args -> "value"))
            .build();

        new AgentRunner().addRuntimeEventListener(events::add).run(agent, "lookup");

        int firstModel = indexOf(events, AgentRuntimeEventType.MODEL_STARTED, 0);
        int toolStarted = indexOf(events, AgentRuntimeEventType.TOOL_STARTED, 0);
        int toolCompleted = indexOf(events, AgentRuntimeEventType.TOOL_COMPLETED, 0);
        int secondModel = indexOf(events, AgentRuntimeEventType.MODEL_STARTED, firstModel + 1);
        assertTrue(firstModel < toolStarted);
        assertTrue(toolStarted < toolCompleted);
        assertTrue(toolCompleted < secondModel);
        assertEquals(1, count(events, AgentRuntimeEventType.RUN_COMPLETED));
    }

    @Test
    public void shouldContinuePublishingWhenListenerFails() {
        AgentRuntimeEventStream stream = new AgentRuntimeEventStream();
        AgentRun run = newRun("listener-failure");
        AtomicInteger received = new AtomicInteger();
        stream.addListener(event -> { throw new RuntimeException("listener failed"); });
        stream.addListener(event -> received.incrementAndGet());

        AgentRuntimeEvent event = stream.publish(run, AgentRuntimeEventType.RUN_STARTED,
            Collections.singletonMap("value", "ok"));

        assertEquals(1, received.get());
        assertEquals(1L, event.getSequence());
    }

    @Test
    public void shouldStopDeliveringAfterListenerIsRemoved() {
        AgentRuntimeEventStream stream = new AgentRuntimeEventStream();
        AgentRun run = newRun("listener-removal");
        AtomicInteger received = new AtomicInteger();
        AgentRuntimeEventListener listener = event -> received.incrementAndGet();
        stream.addListener(listener);
        stream.publish(run, AgentRuntimeEventType.RUN_STARTED, null);
        stream.removeListener(listener);

        stream.publish(run, AgentRuntimeEventType.STEP_STARTED, null);

        assertEquals(1, received.get());
    }

    @Test
    public void shouldAllocateUniqueSequencesDuringConcurrentPublishing() throws Exception {
        AgentRuntimeEventStream stream = new AgentRuntimeEventStream();
        AgentRun run = newRun("concurrent-sequence");
        List<Long> sequences = Collections.synchronizedList(new ArrayList<Long>());
        stream.addListener(event -> sequences.add(event.getSequence()));
        int threads = 8;
        int eventsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                await(start);
                for (int j = 0; j < eventsPerThread; j++) {
                    stream.publish(run, AgentRuntimeEventType.TOOL_PROGRESS, null);
                }
            });
        }
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(threads * eventsPerThread, sequences.size());
        Set<Long> unique = new HashSet<>(sequences);
        assertEquals(sequences.size(), unique.size());
        assertEquals(Long.valueOf(1), Collections.min(unique));
        assertEquals(Long.valueOf(threads * eventsPerThread), Collections.max(unique));
    }

    @Test
    public void shouldKeepSequencesIndependentAcrossRuns() {
        AgentRuntimeEventStream stream = new AgentRuntimeEventStream();
        AgentRun first = newRun("sequence-a");
        AgentRun second = newRun("sequence-b");

        assertEquals(1L, stream.publish(first, AgentRuntimeEventType.RUN_STARTED, null)
            .getSequence());
        assertEquals(2L, stream.publish(first, AgentRuntimeEventType.STEP_STARTED, null)
            .getSequence());
        assertEquals(1L, stream.publish(second, AgentRuntimeEventType.RUN_STARTED, null)
            .getSequence());
    }

    @Test
    public void shouldExposeImmutableEventData() {
        AgentRuntimeEvent event = new AgentRuntimeEvent("run", "run", null,
            "agent", "1", 1, AgentRuntimeEventType.RUN_STARTED,
            Collections.singletonMap("key", "value"));
        try {
            event.getData().put("other", "value");
            fail("event data must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals("value", event.getData().get("key"));
        }
    }

    private AgentRun newRun(String name) {
        Agent agent = Agent.builder(name)
            .chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .build();
        return new AgentRunner().start(agent, "input");
    }

    private void assertTypes(List<AgentRuntimeEvent> events,
                             AgentRuntimeEventType... expected) {
        List<AgentRuntimeEventType> actual = new ArrayList<>();
        for (AgentRuntimeEvent event : events) actual.add(event.getType());
        assertEquals(java.util.Arrays.asList(expected), actual);
    }

    private void assertStrictSequence(List<AgentRuntimeEvent> events) {
        long previous = 0;
        for (AgentRuntimeEvent event : events) {
            assertTrue(event.getSequence() > previous);
            previous = event.getSequence();
        }
    }

    private int indexOf(List<AgentRuntimeEvent> events, AgentRuntimeEventType type,
                        int fromIndex) {
        for (int i = fromIndex; i < events.size(); i++) {
            if (events.get(i).getType() == type) return i;
        }
        return -1;
    }

    private int count(List<AgentRuntimeEvent> events, AgentRuntimeEventType type) {
        int result = 0;
        for (AgentRuntimeEvent event : events) if (event.getType() == type) result++;
        return result;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
