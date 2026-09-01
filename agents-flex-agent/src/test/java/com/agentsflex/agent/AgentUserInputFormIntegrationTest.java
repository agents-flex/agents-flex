/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.message.AgentFormMessage;
import com.agentsflex.agent.exception.AgentFormRequiredException;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.tool.AgentFormDefinition;
import com.agentsflex.agent.tool.AgentToolContext;
import com.agentsflex.agent.tool.AgentUserInputTool;
import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.core.memory.DefaultChatMemory;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.alibaba.fastjson2.JSON;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** request_user_input 控制工具和表单消息的完整恢复协议测试。 */
public class AgentUserInputFormIntegrationTest {

    @Test
    public void shouldSuspendBusinessToolAndRetryWithSubmittedFormData() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(
            new ToolCall("ticket-call", "create_support_ticket", "{}")));
        model.enqueue(prompt -> {
            ToolMessage result = lastToolMessage(prompt.getMessages());
            assertEquals("ticket-call", result.getToolCallId());
            assertEquals("TICKET-1001", result.getContent());
            return new AiMessage("工单已经创建");
        });

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("title", "补充故障信息");
        schema.put("properties", new LinkedHashMap<String, Object>());
        AgentFormDefinition formDefinition = AgentFormDefinition
            .builder("support_ticket_details")
            .description("工具执行时发现缺少故障影响信息")
            .schema(schema)
            .build();
        AtomicInteger attempts = new AtomicInteger();

        Agent agent = Agent.builder("tool-form-agent")
            .chatModel(model)
            .tool(AgentScenarioTestSupport.tool("create_support_ticket", arguments -> {
                attempts.incrementAndGet();
                AgentToolContext context = AgentToolContext.current();
                assertTrue(context != null);
                Map<String, Object> submitted = context.getSubmittedFormData();
                if (submitted.isEmpty()) {
                    throw new AgentFormRequiredException(formDefinition);
                }
                assertEquals("统一登录系统", submitted.get("affectedSystem"));
                assertEquals("ALL_USERS", submitted.get("impactScope"));
                return "TICKET-1001";
            }))
            .executionPolicy(AgentExecutionPolicy.builder()
                .budget(AgentBudget.builder().maxToolCalls(1).build())
                .build())
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        InMemoryAgentLoader loader = new InMemoryAgentLoader(agent);
        DefaultChatMemory memory = new DefaultChatMemory("tool-form-conversation");
        List<AgentEvent> events = new ArrayList<>();
        AgentRunner firstRunner = AgentRunner.builder()
            .turnStore(store)
            .agentLoader(loader)
            .chatMemoryProvider(id -> memory)
            .build()
            .addEventListener(events::add);

        AgentTurn waiting = firstRunner.run(
            agent, "tool-form-conversation", "创建登录故障工单");

        assertEquals(AgentTurnStatus.WAITING_FOR_USER, waiting.getStatus());
        assertEquals(AgentTurnExecutionPoint.PROCESS_TOOLS, waiting.getExecutionPoint());
        assertEquals("ticket-call", waiting.getSuspension().getCorrelationId());
        assertEquals("TOOL", waiting.getSuspension().getMetadata().get("inputTarget"));
        assertEquals("create_support_ticket",
            waiting.getSuspension().getMetadata().get("toolName"));
        assertEquals(1, waiting.getPendingToolCalls().size());
        assertEquals("create_support_ticket", waiting.getPendingToolCalls().get(0).getName());
        assertEquals(1, attempts.get());
        assertEquals(1, count(events, AgentEventType.TOOL_STARTED));
        assertEquals(1, count(events, AgentEventType.TOOL_INPUT_REQUESTED));
        assertEquals(0, count(events, AgentEventType.TOOL_FAILED));
        assertBefore(events, AgentEventType.TOOL_STARTED,
            AgentEventType.TOOL_INPUT_REQUESTED);
        assertBefore(events, AgentEventType.TOOL_INPUT_REQUESTED,
            AgentEventType.STEP_COMPLETED);
        assertBefore(events, AgentEventType.STEP_COMPLETED,
            AgentEventType.TURN_SUSPENDED);

        AgentFormMessage pending = form(memory);
        assertEquals("support_ticket_details", pending.getFormKey());
        assertEquals(schema, pending.getSchema());
        assertEquals(AgentFormMessage.Status.PENDING, pending.getStatus());

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("affectedSystem", "统一登录系统");
        values.put("impactScope", "ALL_USERS");
        AgentRunner secondRunner = AgentRunner.builder()
            .turnStore(store)
            .agentLoader(loader)
            .chatMemoryProvider(id -> memory)
            .build()
            .addEventListener(events::add);
        AgentTurn runnable = secondRunner.submitResume(waiting.getId(),
            AgentResumeCommand.userInput("ticket-call", values)
                .withMetadata("submittedBy", "user-8"));

        assertEquals(AgentTurnStatus.RUNNING, runnable.getStatus());
        assertEquals(AgentTurnExecutionPoint.PROCESS_TOOLS, runnable.getExecutionPoint());
        assertEquals(values, runnable.getToolInputData("ticket-call"));
        AgentFormMessage submitted = form(memory);
        assertEquals(AgentFormMessage.Status.SUBMITTED, submitted.getStatus());
        assertEquals("统一登录系统",
            submitted.getSubmittedValues().get("affectedSystem"));
        assertEquals("user-8", submitted.getSubmittedBy());

        AgentTurn completed = secondRunner.runUntilBlocked(waiting.getId());

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals("工单已经创建", completed.getFinalOutput());
        assertEquals(2, attempts.get());
        assertEquals(1, completed.getToolCallCount());
        assertEquals(2, count(events, AgentEventType.TOOL_STARTED));
        assertEquals(1, count(events, AgentEventType.TOOL_COMPLETED));
        assertEquals(0, count(events, AgentEventType.TOOL_FAILED));
    }

    @Test
    public void shouldSuspendForFormAndResumeWithMatchingToolMessage() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            assertTrue(prompt.getToolsMap().containsKey(AgentUserInputTool.NAME));
            return toolCalls(new ToolCall("input-1", AgentUserInputTool.NAME,
                "{\"formKey\":\"support_ticket_details\"}"));
        });
        model.enqueue(prompt -> {
            ToolMessage input = lastToolMessage(prompt.getMessages());
            assertEquals("input-1", input.getToolCallId());
            Map<String, Object> body = JSON.parseObject(input.getContent());
            assertEquals("submitted", body.get("status"));
            assertEquals("support_ticket_details", body.get("formKey"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            assertEquals("登录系统", data.get("affectedSystem"));
            assertEquals("ALL_USERS", data.get("impactScope"));
            return new AiMessage("工单信息已确认");
        });

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("affectedSystem", field("string", "受影响系统"));
        properties.put("impactScope", enumField("影响范围",
            "ONE_USER", "PARTIAL_USERS", "ALL_USERS"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("title", "补充故障信息");
        schema.put("properties", properties);
        schema.put("required", java.util.Arrays.asList("affectedSystem", "impactScope"));

        Agent agent = Agent.builder("form-agent")
            .chatModel(model)
            .tool(AgentUserInputTool.builder()
                .form(AgentFormDefinition.builder("support_ticket_details")
                    .description("创建故障工单缺少受影响系统或影响范围时使用")
                    .schema(schema)
                    .build())
                .build())
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        InMemoryAgentLoader loader = new InMemoryAgentLoader(agent);
        DefaultChatMemory memory = new DefaultChatMemory("form-conversation");
        AgentRunner runner = AgentRunner.builder()
            .turnStore(store)
            .agentLoader(loader)
            .chatMemoryProvider(id -> memory)
            .build();

        AgentTurn waiting = runner.run(agent, "form-conversation", "创建高优先级故障工单");

        assertEquals(AgentTurnStatus.WAITING_FOR_USER, waiting.getStatus());
        assertEquals(AgentTurnExecutionPoint.PROCESS_TOOLS, waiting.getExecutionPoint());
        assertEquals("input-1", waiting.getSuspension().getCorrelationId());
        assertEquals("support_ticket_details",
            waiting.getSuspension().getMetadata().get("formKey"));
        assertEquals(schema, waiting.getSuspension().getMetadata().get("schema"));
        assertEquals(1, waiting.getPendingToolCalls().size());
        AgentFormMessage pending = form(memory);
        assertEquals(AgentFormMessage.Status.PENDING, pending.getStatus());
        assertEquals("补充故障信息", pending.getContent());
        assertEquals(schema, pending.getSchema());
        assertFalse(pending.containsMetadata("schema"));
        assertEquals(1, pending.getActions().size());
        assertFalse(pending.isModelVisible());

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("affectedSystem", "登录系统");
        values.put("impactScope", "ALL_USERS");
        try {
            runner.submitResume(waiting.getId(),
                AgentResumeCommand.userInput("other-input", values));
            fail("mismatched input request must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("correlationId"));
        }

        AgentTurn runnable = runner.submitResume(waiting.getId(),
            AgentResumeCommand.userInput("input-1", values)
                .withMetadata("submittedBy", "user-7"));

        assertEquals(AgentTurnStatus.RUNNING, runnable.getStatus());
        assertEquals(AgentTurnExecutionPoint.INVOKE_MODEL, runnable.getExecutionPoint());
        assertTrue(runnable.getPendingToolCalls().isEmpty());
        AgentFormMessage submitted = form(memory);
        assertEquals(AgentFormMessage.Status.SUBMITTED, submitted.getStatus());
        assertEquals("user-7", submitted.getSubmittedBy());
        assertEquals("登录系统", submitted.getSubmittedValues().get("affectedSystem"));
        assertTrue(submitted.getActions().isEmpty());
        assertEquals(1, submitted.getVersion());

        AgentTurn completed = runner.runUntilBlocked(waiting.getId());
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals("工单信息已确认", completed.getFinalOutput());
        assertEquals(4, memory.getModelMessages(Integer.MAX_VALUE).size());
        assertEquals(5, memory.getMessages(Integer.MAX_VALUE).size());
    }

    @Test
    public void shouldLetModelChooseBusinessToolAfterFormSubmission() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("input-1", AgentUserInputTool.NAME,
            "{\"formKey\":\"meeting_room_booking\"}")));
        model.enqueue(prompt -> {
            ToolMessage input = lastToolMessage(prompt.getMessages());
            assertEquals("input-1", input.getToolCallId());
            Map<String, Object> body = JSON.parseObject(input.getContent());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            assertEquals("季度评审", data.get("subject"));
            return toolCalls(new ToolCall("reserve-call", "reserve_meeting_room",
                JSON.toJSONString(data)));
        });
        model.enqueue(prompt -> {
            ToolMessage result = lastToolMessage(prompt.getMessages());
            assertEquals("reserve-call", result.getToolCallId());
            assertEquals("ROOM-101", result.getContent());
            return new AiMessage("会议室预定成功");
        });

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("title", "填写会议安排");
        schema.put("properties", new LinkedHashMap<String, Object>());
        AgentFormDefinition form = AgentFormDefinition.builder("meeting_room_booking")
            .description("预定会议室时收集会议资料")
            .schema(schema)
            .build();
        Agent agent = Agent.builder("target-form-agent")
            .chatModel(model)
            .tool(AgentUserInputTool.builder().form(form).build())
            .tool(AgentScenarioTestSupport.tool("reserve_meeting_room", arguments -> "ROOM-101"))
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        DefaultChatMemory memory = new DefaultChatMemory("target-form-conversation");
        AgentRunner runner = AgentRunner.builder().turnStore(store)
            .agentLoader(new InMemoryAgentLoader(agent))
            .chatMemoryProvider(id -> memory).build();

        AgentTurn waiting = runner.run(agent, "target-form-conversation", "预定会议室");
        assertEquals(AgentTurnStatus.WAITING_FOR_USER, waiting.getStatus());

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("subject", "季度评审");
        values.put("preferredTime", "明天下午三点");
        values.put("participantCount", 8);
        AgentTurn runnable = runner.submitResume(waiting.getId(),
            AgentResumeCommand.userInput("input-1", values));
        assertEquals(AgentTurnStatus.RUNNING, runnable.getStatus());
        assertTrue(runnable.getPendingToolCalls().isEmpty());
        assertEquals(AgentTurnExecutionPoint.INVOKE_MODEL, runnable.getExecutionPoint());
        assertEquals(AgentTurnStatus.COMPLETED,
            runner.runUntilBlocked(waiting.getId()).getStatus());
        assertEquals("会议室预定成功", runner.restore(waiting.getId()).getFinalOutput());
    }

    @Test
    public void shouldSerializeFormMessageStorageFields() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("title", "补充信息");
        AgentFormMessage source = AgentFormMessage.request(
            "turn-1", "input-1", "ticket", schema);
        source.setVersion(3);

        AgentFormMessage restored = JSON.parseObject(
            JSON.toJSONString(source), AgentFormMessage.class);

        assertEquals(source.getMessageId(), restored.getMessageId());
        assertEquals(3, restored.getVersion());
        assertFalse(restored.isModelVisible());
        assertEquals("ticket", restored.getFormKey());
        assertEquals(schema, restored.getSchema());
        assertEquals(AgentFormMessage.Status.PENDING, restored.getStatus());
    }

    private static Map<String, Object> field(String type, String title) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", type);
        field.put("title", title);
        return field;
    }

    private static Map<String, Object> enumField(String title, String... values) {
        Map<String, Object> field = field("string", title);
        field.put("enum", java.util.Arrays.asList(values));
        return field;
    }

    private static ToolMessage lastToolMessage(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof ToolMessage) {
                return (ToolMessage) messages.get(index);
            }
        }
        throw new AssertionError("ToolMessage not found");
    }

    private static AgentFormMessage form(DefaultChatMemory memory) {
        for (Message message : memory.getMessages(Integer.MAX_VALUE)) {
            if (message instanceof AgentFormMessage) return (AgentFormMessage) message;
        }
        throw new AssertionError("AgentFormMessage not found");
    }

    private static int count(List<AgentEvent> events, AgentEventType type) {
        int result = 0;
        for (AgentEvent event : events) {
            if (event.getType() == type) result++;
        }
        return result;
    }

    private static void assertBefore(List<AgentEvent> events,
                                     AgentEventType first, AgentEventType second) {
        int firstIndex = -1;
        int secondIndex = -1;
        for (int index = 0; index < events.size(); index++) {
            if (firstIndex < 0 && events.get(index).getType() == first) firstIndex = index;
            if (secondIndex < 0 && events.get(index).getType() == second) secondIndex = index;
        }
        assertTrue(first + " must be before " + second,
            firstIndex >= 0 && secondIndex > firstIndex);
    }
}
