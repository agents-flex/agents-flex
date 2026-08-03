/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent.store;

import com.agentsflex.agent.AgentExecutionPolicy;
import com.agentsflex.agent.AgentRunPhase;
import com.agentsflex.agent.AgentRunSnapshot;
import com.agentsflex.agent.AgentRunStatus;
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

        AgentRunSnapshot snapshot = AgentRunSnapshot.builder("run-1", "agent-1", "v1")
            .executionPolicy(AgentExecutionPolicy.defaults())
            .status(AgentRunStatus.WAITING_FOR_APPROVAL)
            .phase(AgentRunPhase.TOOLS)
            .messages(Arrays.<Message>asList(user, assistant, tool))
            .pendingToolCalls(Collections.singletonList(toolCall))
            .suspension(AgentSuspension.toolApproval("call-1", "lookup"))
            .stepCount(3)
            .metadata(metadata)
            .build();

        FastjsonAgentStoreSerializer serializer = new FastjsonAgentStoreSerializer();
        byte[] encoded = serializer.serialize(snapshot);
        AgentRunSnapshot decoded = serializer.deserialize(encoded, AgentRunSnapshot.class);

        assertEquals("run-1", decoded.getRunId());
        assertEquals(AgentRunStatus.WAITING_FOR_APPROVAL, decoded.getStatus());
        assertTrue(decoded.getMessages().get(0) instanceof UserMessage);
        assertTrue(decoded.getMessages().get(1) instanceof AiMessage);
        assertTrue(decoded.getMessages().get(2) instanceof ToolMessage);
        assertEquals("https://example.com/image.png",
            ((UserMessage) decoded.getMessages().get(0)).getImageUrls().get(0));
        assertEquals("lookup", decoded.getPendingToolCalls().get(0).getName());
        assertEquals(3, decoded.getStepCount());
        assertEquals("tenant-1", decoded.getMetadata().get("tenant"));
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
        AgentRunSnapshot snapshot = AgentRunSnapshot.builder("run-custom", "agent", "v1")
            .executionPolicy(AgentExecutionPolicy.defaults())
            .metadata(metadata)
            .build();
        byte[] encoded = new FastjsonAgentStoreSerializer().serialize(snapshot);

        AgentRunSnapshot untrusted = new FastjsonAgentStoreSerializer()
            .deserialize(encoded, AgentRunSnapshot.class);
        assertTrue("untrusted type must not be instantiated",
            !(untrusted.getMetadata().get("custom") instanceof CustomState));

        FastjsonAgentStoreSerializer trusted = new FastjsonAgentStoreSerializer(
            "com.example.agentstate.");
        AgentRunSnapshot decoded = trusted.deserialize(encoded, AgentRunSnapshot.class);
        assertTrue(decoded.getMetadata().get("custom") instanceof CustomState);
        assertEquals("ready", ((CustomState) decoded.getMetadata().get("custom")).getValue());
    }

    @Test
    public void shouldRejectNestedNonSerializableValue() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("invalid", new Object());
        AgentRunSnapshot snapshot = AgentRunSnapshot.builder("run-invalid", "agent", "v1")
            .executionPolicy(AgentExecutionPolicy.defaults())
            .metadata(metadata)
            .build();

        try {
            new FastjsonAgentStoreSerializer().serialize(snapshot);
            fail("non-serializable metadata value must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("non-serializable"));
        }
    }
}
