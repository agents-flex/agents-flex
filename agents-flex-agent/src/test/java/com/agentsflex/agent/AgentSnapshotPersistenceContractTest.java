/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Snapshot 序列化与不可变状态边界测试。
 */
public class AgentSnapshotPersistenceContractTest {

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
        AgentTurn turn = new AgentRunner().start(agent, "你好, serialization",
            AgentTurnOptions.builder().streaming(true).build());
        turn.putMetadata("tenant", "t-1");
        AgentTurnSnapshot original = turn.toSnapshot();
        AgentTurn restoredTurn = AgentTurn.fromSnapshot(agent, original);
        assertTrue(restoredTurn.isStreaming());

        AgentTurnSnapshot restored = roundTrip(original);

        assertEquals(original.getState().getTurnId(), restored.getState().getTurnId());
        assertEquals("7", restored.getAgentVersion());
        assertEquals(9, restored.getState().getExecutionPolicy().getMaxIterations());
        assertEquals(2, restored.getState().getExecutionPolicy().getRetryPolicy().getMaxRetries());
        assertEquals(100, restored.getState().getExecutionPolicy().getBudget().getMaxTotalTokens());
        assertEquals("你好, serialization", restored.getState().getMessages().get(0).getTextContent());
        assertTrue(restored.getState().isStreaming());
        assertEquals("t-1", restored.getState().getMetadata().get("tenant"));
        assertTrue(restored.getState().isImmutable());
    }

    @Test
    public void shouldKeepSnapshotStateImmutableAndIsolatedFromBuilderChanges() {
        AgentTurnState state = AgentTurnState.builder("turn-1",
                AgentExecutionPolicy.defaults(), 0)
            .status(AgentTurnStatus.READY)
            .messages(Collections.singletonList(new UserMessage("original")))
            .metadata(Collections.<String, Object>singletonMap("tenant", "t-1"))
            .build();
        AgentTurnSnapshot original = AgentTurnSnapshot.of("agent", "1", state);

        AgentTurnSnapshot changed = original.withState(original.getState().toBuilder()
            .status(AgentTurnStatus.RUNNING)
            .metadata(Collections.<String, Object>singletonMap("tenant", "t-2"))
            .build());

        assertTrue(original.getState().isImmutable());
        assertEquals(AgentTurnStatus.READY, original.getState().getStatus());
        assertEquals("t-1", original.getState().getMetadata().get("tenant"));
        assertEquals(AgentTurnStatus.RUNNING, changed.getState().getStatus());
        assertEquals("t-2", changed.getState().getMetadata().get("tenant"));
        try {
            original.getState().setStatus(AgentTurnStatus.FAILED);
            fail("snapshot state must reject mutation");
        } catch (UnsupportedOperationException expected) {
            assertEquals("AgentTurnState is immutable", expected.getMessage());
        }
    }

    @Test
    public void shouldRejectNonSerializableSnapshotMetadata() throws Exception {
        AgentTurn turn = new AgentRunner().start(Agent.builder("bad-metadata")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build(), "input");
        turn.putMetadata("service", new Object());
        try {
            serialize(turn.toSnapshot());
            fail("non-serializable metadata must be rejected");
        } catch (NotSerializableException expected) {
            assertTrue(expected.getMessage().contains("java.lang.Object"));
        }
    }

    @Test
    public void shouldRebindTransientErrorFactoryAndRetryDeciderAfterSnapshotRestore() throws Exception {
        AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
            .toolErrorMessageFactory((turn, call, error) -> {
                ToolMessage message = new ToolMessage();
                message.setContent("custom-error");
                return message;
            })
            .retryDecider((turn, error, call) -> false)
            .build();
        Agent originalAgent = Agent.builder("runtime-policy")
            .version("v1").chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .executionPolicy(policy).build();
        AgentTurn originalTurn = new AgentRunner().start(originalAgent, "input");
        Agent restoredAgent = Agent.builder("runtime-policy")
            .version("v1").chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .executionPolicy(policy).build();
        AgentTurn restored = AgentTurn.fromSnapshot(restoredAgent, roundTrip(originalTurn.toSnapshot()));
        ToolMessage custom = restored.getExecutionPolicy().getToolErrorMessageFactory().create(
            restored, new ToolCall("call", "lookup", "{}"), new IllegalStateException("hidden"));
        assertEquals("custom-error", custom.getContent());
        assertTrue(!restored.getExecutionPolicy().getRetryDecider()
            .shouldRetry(restored, new IllegalStateException("transient"), null));
    }

    private AgentTurnSnapshot roundTrip(AgentTurnSnapshot snapshot) throws Exception {
        byte[] bytes = serialize(snapshot);
        ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes));
        return (AgentTurnSnapshot) input.readObject();
    }

    private byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ObjectOutputStream output = new ObjectOutputStream(buffer);
        output.writeObject(value);
        output.close();
        return buffer.toByteArray();
    }

}
