/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventListener;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

/** 统一事件监听器的顺序、隔离、并发和不可变性契约测试。 */
public class AgentEventListenerContractTest {

    @Test
    public void shouldEmitCanonicalOrderForPlainModelResponse() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("done"));
        List<AgentEvent> events = new ArrayList<>();

        AgentTurn turn = new AgentRunner().addEventListener(events::add)
            .run(Agent.builder("plain-events").chatModel(model).build(), "hello");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertTypes(events,
            AgentEventType.SNAPSHOT_SAVED,
            AgentEventType.STEP_STARTED,
            AgentEventType.TURN_STARTED,
            AgentEventType.MODEL_STARTED,
            AgentEventType.MODEL_COMPLETED,
            AgentEventType.SNAPSHOT_SAVED,
            AgentEventType.TURN_COMPLETED,
            AgentEventType.STEP_COMPLETED);
        assertStrictSequence(events);
    }

    @Test
    public void shouldEmitToolCycleBeforeSecondModelCall() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("call-1", "lookup", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        List<AgentEvent> events = new ArrayList<>();
        Agent agent = Agent.builder("tool-events")
            .chatModel(model).tool(tool("lookup", args -> "value")).build();

        new AgentRunner().addEventListener(events::add).run(agent, "lookup");

        int firstModel = indexOf(events, AgentEventType.MODEL_STARTED, 0);
        int toolStarted = indexOf(events, AgentEventType.TOOL_STARTED, 0);
        int toolCompleted = indexOf(events, AgentEventType.TOOL_COMPLETED, 0);
        int secondModel = indexOf(events, AgentEventType.MODEL_STARTED, firstModel + 1);
        assertTrue(firstModel < toolStarted);
        assertTrue(toolStarted < toolCompleted);
        assertTrue(toolCompleted < secondModel);
        assertEquals(1, count(events, AgentEventType.TURN_COMPLETED));
    }

    @Test
    public void shouldIsolateListenerFailureAndAllowRemoval() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("done"));
        AtomicInteger received = new AtomicInteger();
        AtomicInteger removed = new AtomicInteger();
        AgentEventListener removedListener = event -> removed.incrementAndGet();
        AgentRunner runner = new AgentRunner()
            .addEventListener(event -> { throw new RuntimeException("listener failed"); })
            .addEventListener(removedListener)
            .addEventListener(event -> received.incrementAndGet())
            .removeEventListener(removedListener);

        runner.run(Agent.builder("listener-isolation").chatModel(model).build(), "input");

        assertTrue(received.get() > 0);
        assertEquals(0, removed.get());
    }

    @Test
    public void shouldKeepSequencesIndependentAcrossConcurrentRuns() throws Exception {
        AgentRunner runner = new AgentRunner();
        Map<String, List<Long>> sequences = new ConcurrentHashMap<>();
        runner.addEventListener(event -> sequences
            .computeIfAbsent(event.getTurnId(), key -> Collections.synchronizedList(new ArrayList<Long>()))
            .add(event.getSequence()));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++) {
            final int index = i;
            executor.submit(() -> {
                AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
                model.enqueue(prompt -> new AiMessage("done"));
                runner.run(Agent.builder("concurrent-event-" + index).chatModel(model).build(), "input");
            });
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(8, sequences.size());
        for (List<Long> runSequences : sequences.values()) {
            assertEquals(Long.valueOf(1), runSequences.get(0));
            long previous = 0;
            for (Long sequence : runSequences) {
                assertTrue(sequence > previous);
                previous = sequence;
            }
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void shouldDeeplyFreezeEventData() {
        List<String> nested = new ArrayList<>();
        nested.add("value");
        AgentEvent event = new AgentEvent("turn", "turn", null,
            "agent", "1", 1, AgentEventType.TURN_STARTED,
            Collections.singletonMap("nested", nested));
        nested.add("changed later");

        assertEquals(1, ((List<?>) event.getData().get("nested")).size());
        try {
            ((List) event.getData().get("nested")).add("other");
            fail("nested event data must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertFalse(event.getData().isEmpty());
        }
    }

    private void assertTypes(List<AgentEvent> events, AgentEventType... expected) {
        List<AgentEventType> actual = new ArrayList<>();
        for (AgentEvent event : events) actual.add(event.getType());
        assertEquals(java.util.Arrays.asList(expected), actual);
    }

    private void assertStrictSequence(List<AgentEvent> events) {
        long previous = 0;
        for (AgentEvent event : events) {
            assertTrue(event.getSequence() > previous);
            previous = event.getSequence();
        }
    }

    private int indexOf(List<AgentEvent> events, AgentEventType type, int fromIndex) {
        for (int i = fromIndex; i < events.size(); i++) {
            if (events.get(i).getType() == type) return i;
        }
        return -1;
    }

    private int count(List<AgentEvent> events, AgentEventType type) {
        int result = 0;
        for (AgentEvent event : events) if (event.getType() == type) result++;
        return result;
    }
}
