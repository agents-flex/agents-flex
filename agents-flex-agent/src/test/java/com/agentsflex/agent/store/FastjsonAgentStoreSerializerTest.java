/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.store;

import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.AgentTurnPhase;
import com.agentsflex.agent.AgentTurnSnapshot;
import com.agentsflex.agent.AgentTurnState;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.AgentSuspension;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.example.agentstate.CustomState;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** fastjson2 Agent Store 默认序列化器的往返和类型安全测试。 */
public class FastjsonAgentStoreSerializerTest {

    @Test
    public void shouldRoundTripSnapshotWithPolymorphicMessagesAndState() {
        UserMessage user = new UserMessage("分析图片");
        user.addImageUrl("https://example.com/image.png");

        ToolCall toolCall = new ToolCall();
        toolCall.setId("call-1");
        toolCall.setName("lookup");
        toolCall.setArguments("{\"query\":\"agents-flex\"}");

        AiMessage assistant = new AiMessage("准备调用工具");
        assistant.setToolCalls(Collections.singletonList(toolCall));

        ToolMessage tool = new ToolMessage();
        tool.setToolCallId("call-1");
        tool.setContent("查询完成");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenant", "tenant-1");
        metadata.put("priority", 3);
        metadata.put("labels", Arrays.asList("durable", "jsonb"));
        Map<String, Object> submittedForm = new LinkedHashMap<>();
        submittedForm.put("affectedSystem", "登录系统");
        Map<String, Map<String, Object>> toolInputData = new LinkedHashMap<>();
        toolInputData.put("call-1", submittedForm);

        AgentTurnState state = AgentTurnState.builder("turn-1",
                AgentExecutionPolicy.defaults(), 0)
            .status(AgentTurnStatus.WAITING_FOR_APPROVAL)
            .phase(AgentTurnPhase.TOOLS)
            .messages(Arrays.<Message>asList(user, assistant, tool))
            .pendingToolCalls(Collections.singletonList(toolCall))
            .suspension(AgentSuspension.toolApproval("call-1", "lookup"))
            .toolInputData(toolInputData)
            .stepCount(3)
            .metadata(metadata)
            .build();
        AgentTurnSnapshot snapshot = AgentTurnSnapshot.of("agent-1", "v1", state);

        FastjsonAgentStoreSerializer serializer = new FastjsonAgentStoreSerializer();
        byte[] encoded = serializer.serialize(snapshot);
        AgentTurnSnapshot decoded = serializer.deserialize(encoded, AgentTurnSnapshot.class);

        assertEquals("turn-1", decoded.getState().getTurnId());
        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, decoded.getState().getStatus());
        assertTrue(decoded.getState().getMessages().get(0) instanceof UserMessage);
        assertTrue(decoded.getState().getMessages().get(1) instanceof AiMessage);
        assertTrue(decoded.getState().getMessages().get(2) instanceof ToolMessage);
        assertEquals("https://example.com/image.png",
            ((UserMessage) decoded.getState().getMessages().get(0)).getImageUrls().get(0));
        assertEquals("lookup", decoded.getState().getPendingToolCalls().get(0).getName());
        assertEquals(3, decoded.getState().getStepCount());
        assertEquals("tenant-1", decoded.getState().getMetadata().get("tenant"));
        assertEquals("登录系统", decoded.getState().getToolInputData()
            .get("call-1").get("affectedSystem"));
    }

    @Test
    public void shouldRoundTripBinaryValueAndNull() {
        FastjsonAgentStoreSerializer serializer = new FastjsonAgentStoreSerializer();
        byte[] value = "工具结果".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(value, serializer.deserialize(serializer.serialize(value), byte[].class));
        assertNull(serializer.deserialize(null, byte[].class));
    }

    @Test
    public void shouldOnlyRestoreWhitelistedCustomMetadataType() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("custom", new CustomState("ready"));
        AgentTurnState state = AgentTurnState.builder("turn-custom",
                AgentExecutionPolicy.defaults(), 0)
            .metadata(metadata)
            .build();
        AgentTurnSnapshot snapshot = AgentTurnSnapshot.of("agent", "v1", state);
        byte[] encoded = new FastjsonAgentStoreSerializer().serialize(snapshot);

        AgentTurnSnapshot untrusted = new FastjsonAgentStoreSerializer()
            .deserialize(encoded, AgentTurnSnapshot.class);
        assertTrue("untrusted type must not be instantiated",
            !(untrusted.getState().getMetadata().get("custom") instanceof CustomState));

        FastjsonAgentStoreSerializer trusted = new FastjsonAgentStoreSerializer(
            "com.example.agentstate.");
        AgentTurnSnapshot decoded = trusted.deserialize(encoded, AgentTurnSnapshot.class);
        assertTrue(decoded.getState().getMetadata().get("custom") instanceof CustomState);
        assertEquals("ready",
            ((CustomState) decoded.getState().getMetadata().get("custom")).getValue());
    }

    @Test
    public void shouldRejectNestedNonSerializableValue() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("invalid", new Object());
        AgentTurnState state = AgentTurnState.builder("turn-invalid",
                AgentExecutionPolicy.defaults(), 0)
            .metadata(metadata)
            .build();
        AgentTurnSnapshot snapshot = AgentTurnSnapshot.of("agent", "v1", state);

        try {
            new FastjsonAgentStoreSerializer().serialize(snapshot);
            fail("non-serializable metadata value must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("non-serializable"));
        }
    }
}
