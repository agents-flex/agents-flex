/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.context.AgentArtifactReference;
import com.agentsflex.agent.context.AgentContextUpdate;
import com.agentsflex.agent.context.InMemoryAgentArtifactStore;
import com.agentsflex.agent.context.MessageCountAgentContextManager;
import com.agentsflex.agent.context.ToolResultOffloadPolicy;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Snapshot 序列化、上下文压缩和 Artifact 的持久化边界测试。 */
public class AgentPersistenceContextContractTest {

    @Test
    public void shouldRoundTripSnapshotThroughJavaSerialization() throws Exception {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        Agent agent = Agent.builder("serializable-agent")
            .version("7")
            .chatModel(model)
            .executionPolicy(AgentExecutionPolicy.builder()
                .maxIterations(9)
                .retryPolicy(AgentRetryPolicy.builder().maxRetries(2).build())
                .budget(AgentBudget.builder().maxTotalTokens(100).build())
                .build())
            .build();
        AgentRun run = new AgentRunner().start(agent, "你好, serialization");
        run.putMetadata("tenant", "t-1");
        AgentRunSnapshot original = run.toSnapshot();

        AgentRunSnapshot restored = roundTrip(original);

        assertEquals(original.getRunId(), restored.getRunId());
        assertEquals("7", restored.getAgentVersion());
        assertEquals(9, restored.getExecutionPolicy().getMaxIterations());
        assertEquals(2, restored.getExecutionPolicy().getRetryPolicy().getMaxRetries());
        assertEquals(100, restored.getExecutionPolicy().getBudget().getMaxTotalTokens());
        assertEquals("你好, serialization", restored.getMessages().get(0).getTextContent());
        assertEquals("t-1", restored.getMetadata().get("tenant"));
    }

    @Test
    public void shouldRejectNonSerializableSnapshotMetadata() throws Exception {
        AgentRun run = new AgentRunner().start(Agent.builder("bad-metadata")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build(), "input");
        run.putMetadata("service", new Object());
        try {
            serialize(run.toSnapshot());
            fail("non-serializable metadata must be rejected");
        } catch (NotSerializableException expected) {
            assertTrue(expected.getMessage().contains("java.lang.Object"));
        }
    }

    @Test
    public void shouldLeaveHistoryUntouchedWhenSummarizerReturnsNull() {
        AgentRun run = runWithMessages("null-summary", 6);
        List<Message> before = run.getPrompt().getMemory().getMessages(Integer.MAX_VALUE);
        MessageCountAgentContextManager manager = new MessageCountAgentContextManager(
            4, 2, (messages, context) -> null);

        AgentContextUpdate update = manager.prepare(run, AgentInvocationContext.empty());

        assertFalse(update.isChanged());
        assertEquals(texts(before), texts(run.getPrompt().getMemory()
            .getMessages(Integer.MAX_VALUE)));
    }

    @Test
    public void shouldLeaveHistoryUntouchedWhenSummarizerFails() {
        AgentRun run = runWithMessages("failed-summary", 6);
        List<String> before = texts(run.getPrompt().getMemory()
            .getMessages(Integer.MAX_VALUE));
        MessageCountAgentContextManager manager = new MessageCountAgentContextManager(
            4, 2, (messages, context) -> { throw new RuntimeException("summary failed"); });

        try {
            manager.prepare(run, AgentInvocationContext.empty());
            fail("summarizer failure must propagate");
        } catch (RuntimeException expected) {
            assertEquals("summary failed", expected.getMessage());
        }
        assertEquals(before, texts(run.getPrompt().getMemory()
            .getMessages(Integer.MAX_VALUE)));
    }

    @Test
    public void shouldNotCompactAgainWhenHistoryIsAlreadyWithinLimit() {
        AgentRun run = runWithMessages("idempotent-summary", 7);
        java.util.concurrent.atomic.AtomicInteger summaries =
            new java.util.concurrent.atomic.AtomicInteger();
        MessageCountAgentContextManager manager = new MessageCountAgentContextManager(
            5, 3, (messages, context) -> {
                summaries.incrementAndGet();
                return "summary";
            });

        AgentContextUpdate first = manager.prepare(run, AgentInvocationContext.empty());
        AgentContextUpdate second = manager.prepare(run, AgentInvocationContext.empty());

        assertTrue(first.isChanged());
        assertFalse(second.isChanged());
        assertEquals(1, summaries.get());
    }

    @Test
    public void shouldPreserveCompleteToolProtocolGroupDuringCompaction() {
        AgentRun run = newRun("tool-boundary");
        run.getPrompt().addUserMessage("old-1");
        run.getPrompt().addAiMessage("old-2");
        AiMessage callMessage = new AiMessage();
        callMessage.setToolCalls(Collections.singletonList(
            new ToolCall("call-1", "lookup", "{}")));
        run.getPrompt().addMessage(callMessage);
        ToolMessage result = new ToolMessage();
        result.setToolCallId("call-1");
        result.setContent("result");
        run.getPrompt().addMessage(result);
        run.getPrompt().addUserMessage("recent");
        MessageCountAgentContextManager manager = new MessageCountAgentContextManager(
            4, 2, (messages, context) -> "summary");

        manager.prepare(run, AgentInvocationContext.empty());

        List<Message> messages = run.getPrompt().getMemory().getMessages(Integer.MAX_VALUE);
        assertTrue(messages.get(1) instanceof AiMessage);
        assertTrue(((AiMessage) messages.get(1)).hasToolCalls());
        assertTrue(messages.get(2) instanceof ToolMessage);
        assertEquals("call-1", ((ToolMessage) messages.get(2)).getToolCallId());
    }

    @Test
    public void shouldStoreUtf8ArtifactSizeAndStableChecksum() {
        InMemoryAgentArtifactStore store = new InMemoryAgentArtifactStore();
        AgentArtifactReference first = store.save("run", "text/plain", "你好",
            Collections.singletonMap("tool", "lookup"));
        AgentArtifactReference second = store.save("run", "text/plain", "你好", null);

        assertEquals(6, first.getSize());
        assertEquals(first.getChecksum(), second.getChecksum());
        assertEquals("你好", store.load(first.getArtifactId()));
        assertEquals("lookup", first.getMetadata().get("tool"));
        assertNull(store.load("missing"));
    }

    @Test
    public void shouldApplyOffloadThresholdAtExactBoundary() {
        ToolResultOffloadPolicy policy = ToolResultOffloadPolicy.largerThan(4);

        assertFalse(policy.shouldOffload("tool", null));
        assertFalse(policy.shouldOffload("tool", "1234"));
        assertTrue(policy.shouldOffload("tool", "12345"));
    }

    private AgentRunSnapshot roundTrip(AgentRunSnapshot snapshot) throws Exception {
        byte[] bytes = serialize(snapshot);
        ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes));
        return (AgentRunSnapshot) input.readObject();
    }

    private byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ObjectOutputStream output = new ObjectOutputStream(buffer);
        output.writeObject(value);
        output.close();
        return buffer.toByteArray();
    }

    private AgentRun runWithMessages(String name, int count) {
        AgentRun run = newRun(name);
        run.getPrompt().getMemory().clear();
        for (int i = 0; i < count; i++) run.getPrompt().addUserMessage("m-" + i);
        return run;
    }

    private AgentRun newRun(String name) {
        return new AgentRunner().start(Agent.builder(name)
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build(), "input");
    }

    private List<String> texts(List<Message> messages) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (Message message : messages) result.add(message.getTextContent());
        return result;
    }
}
