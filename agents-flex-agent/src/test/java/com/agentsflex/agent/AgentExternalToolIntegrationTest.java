/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolExecutionTarget;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 外部执行 ToolCall 的挂起、回传和跨 Runner 恢复契约。
 */
public class AgentExternalToolIntegrationTest {

    @Test
    public void shouldSuspendExternalToolAndResumeWithMatchingToolMessage() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger localInvocations = new AtomicInteger();
        List<AgentEvent> events = new ArrayList<>();
        model.enqueue(prompt -> toolCalls(new ToolCall(
            "browser-call", "read_browser_location", "{\"accuracy\":\"high\"}")));
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertEquals(3, messages.size());
            assertTrue(messages.get(1) instanceof AiMessage);
            assertTrue(messages.get(2) instanceof ToolMessage);
            ToolMessage result = (ToolMessage) messages.get(2);
            assertEquals("browser-call", result.getToolCallId());
            assertEquals("{\"latitude\":30.2,\"longitude\":120.1}", result.getContent());
            return new AiMessage("location received");
        });
        Tool external = Tool.builder("read_browser_location", "读取浏览器定位")
            .metadata("clientCapability", "geolocation")
            .executionTarget(ToolExecutionTarget.EXTERNAL)
            .function(args -> {
                localInvocations.incrementAndGet();
                return "must not run";
            })
            .build();
        Agent agent = Agent.builder("external-tool-agent")
            .chatModel(model)
            .tool(external)
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        InMemoryAgentLoader loader = new InMemoryAgentLoader(agent);
        AgentRunner firstRunner = new AgentRunner(store, loader).addEventListener(events::add);

        AgentTurn waiting = firstRunner.run(agent, "where am I");

        assertEquals(AgentTurnStatus.WAITING_FOR_TOOL, waiting.getStatus());
        assertEquals(AgentSuspensionType.EXTERNAL_TOOL, waiting.getSuspension().getType());
        assertEquals("browser-call", waiting.getSuspension().getCorrelationId());
        assertEquals("{\"accuracy\":\"high\"}",
            waiting.getSuspension().getMetadata().get("arguments"));
        assertEquals(0, localInvocations.get());
        AgentEvent requested = event(events, AgentEventType.EXTERNAL_TOOL_REQUESTED);
        assertEquals("read_browser_location", requested.getData().get("toolName"));
        assertEquals("{\"accuracy\":\"high\"}", requested.getData().get("arguments"));
        assertEquals("geolocation",
            ((Map<?, ?>) requested.getData().get("toolMetadata")).get("clientCapability"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("latitude", 30.2);
        result.put("longitude", 120.1);
        AgentRunner commandRunner = new AgentRunner(store, loader).addEventListener(events::add);
        AgentTurn runnable = commandRunner.submitResume(waiting.getId(),
            AgentResumeCommand.toolResult("browser-call", result)
                .withMetadata("executorId", "web-session-1"));

        assertEquals(AgentTurnStatus.RUNNING, runnable.getStatus());
        assertEquals(AgentTurnExecutionPoint.INVOKE_MODEL, runnable.getExecutionPoint());
        assertTrue(runnable.getPendingToolCalls().isEmpty());
        assertEquals(0, localInvocations.get());
        assertEquals(AgentResumeCommandType.TOOL_RESULT.name(),
            runnable.getMetadata().get("lastResumeCommand"));

        AgentRunner executionRunner = new AgentRunner(store, loader);
        AgentTurn completed = executionRunner.runUntilBlocked(runnable.getId());
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals("location received", completed.getFinalOutput());
        assertEquals(0, localInvocations.get());
        assertEquals(2, model.getCallCount());
    }

    @Test
    public void shouldReturnExternalToolErrorToModel() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("clipboard-call", "read_clipboard", "{}")));
        model.enqueue(prompt -> {
            ToolMessage error = (ToolMessage) prompt.getMessages().get(2);
            assertEquals("clipboard-call", error.getToolCallId());
            assertTrue(error.getContent().contains("PERMISSION_DENIED"));
            assertTrue(error.getContent().contains("clipboard permission denied"));
            return new AiMessage("unable to read clipboard");
        });
        Agent agent = Agent.builder("external-error-agent")
            .chatModel(model)
            .tool(Tool.builder("read_clipboard", "读取剪贴板")
                .executionTarget(ToolExecutionTarget.EXTERNAL)
                .build())
            .build();
        List<AgentEvent> events = new ArrayList<>();
        AgentRunner runner = new AgentRunner().addEventListener(events::add);

        AgentTurn waiting = runner.run(agent, "read clipboard");
        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.toolError("clipboard-call", "PERMISSION_DENIED",
                "clipboard permission denied"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals("unable to read clipboard", completed.getFinalOutput());
        assertTrue(hasEvent(events, AgentEventType.EXTERNAL_TOOL_FAILED));
        assertFalse(hasEvent(events, AgentEventType.TOOL_FAILED));
    }

    @Test
    public void externalToolCanRequireApprovalBeforeDispatch() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("camera-call", "open_camera", "{}")));
        Tool external = Tool.builder("open_camera", "打开摄像头")
            .executionTarget(ToolExecutionTarget.EXTERNAL)
            .build();
        Agent agent = Agent.builder("approved-external-agent")
            .chatModel(model)
            .tool(external)
            .toolApprovalPolicy((turn, call, tool) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        List<AgentEvent> events = new ArrayList<>();
        AgentRunner runner = new AgentRunner().addEventListener(events::add);

        AgentTurn approval = runner.run(agent, "open camera");
        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, approval.getStatus());
        assertFalse(hasEvent(events, AgentEventType.EXTERNAL_TOOL_REQUESTED));

        AgentTurn waiting = runner.resume(approval,
            AgentResumeCommand.approveTool("camera-call"));
        assertEquals(AgentTurnStatus.WAITING_FOR_TOOL, waiting.getStatus());
        assertEquals(AgentSuspensionType.EXTERNAL_TOOL, waiting.getSuspension().getType());
        assertTrue(hasEvent(events, AgentEventType.EXTERNAL_TOOL_REQUESTED));
    }

    @Test
    public void shouldContinueMixedToolCallsInOriginalOrderAfterExternalResult() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        List<String> order = new ArrayList<>();
        model.enqueue(prompt -> toolCalls(
            new ToolCall("local-before", "local_before", "{}"),
            new ToolCall("external-middle", "external_middle", "{}"),
            new ToolCall("local-after", "local_after", "{}")));
        model.enqueue(prompt -> new AiMessage("mixed tools complete"));
        Agent agent = Agent.builder("mixed-external-agent")
            .chatModel(model)
            .tool(Tool.builder("local_before", "local before")
                .function(args -> {
                    order.add("local-before");
                    return "before";
                }).build())
            .tool(Tool.builder("external_middle", "external middle")
                .executionTarget(ToolExecutionTarget.EXTERNAL)
                .build())
            .tool(Tool.builder("local_after", "local after")
                .function(args -> {
                    order.add("local-after");
                    return "after";
                }).build())
            .build();
        AgentRunner runner = new AgentRunner();

        AgentTurn waiting = runner.run(agent, "run mixed tools");
        assertEquals(AgentTurnStatus.WAITING_FOR_TOOL, waiting.getStatus());
        assertEquals(Arrays.asList("local-before"), order);
        assertEquals(2, waiting.getPendingToolCalls().size());
        assertEquals("external-middle", waiting.getPendingToolCalls().get(0).getId());

        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.toolResult("external-middle", "external-result"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(Arrays.asList("local-before", "local-after"), order);
        assertEquals(3, completed.getToolCallCount());
        assertTrue(completed.getPendingToolCalls().isEmpty());
    }

    @Test
    public void shouldRejectWrongCommandOrCorrelationForExternalTool() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("external-call", "client_tool", "{}")));
        Agent agent = Agent.builder("external-validation-agent")
            .chatModel(model)
            .tool(Tool.builder("client_tool", "client tool")
                .executionTarget(ToolExecutionTarget.EXTERNAL)
                .build())
            .build();
        AgentRunner runner = new AgentRunner();
        AgentTurn waiting = runner.run(agent, "run");

        try {
            runner.submitResume(waiting, AgentResumeCommand.userInput("external-call", "result"));
            fail("Expected command type validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("TOOL_RESULT"));
        }
        try {
            runner.submitResume(waiting, AgentResumeCommand.toolResult("other-call", "result"));
            fail("Expected correlation validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("correlationId"));
        }
    }

    @Test
    public void shouldRejectNullExternalResultAndInvalidSuspensionFactoryArguments() {
        try {
            AgentSuspension.externalTool("", "client", "{}", null);
            fail("blank external call id must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("callId"));
        }
        try {
            AgentSuspension.externalTool("call", "client", "{}", null, -1);
            fail("negative external timeout must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("timeout"));
        }
        try {
            AgentSuspension.userInput("input", -1);
            fail("negative user input timeout must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("timeout"));
        }
        try {
            AgentSuspension.toolApproval("call", "client", null, -1);
            fail("negative approval timeout must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("timeout"));
        }
        try {
            AgentSuspension.toolApproval(" ", "client", null);
            fail("blank approval call id must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("callId"));
        }

        AgentSuspension noExpiry = AgentSuspension.userInput("input", 0);
        assertEquals(0L, noExpiry.getTimeoutMillis());
        assertTrue(noExpiry.getRequestedAt() > 0);

        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("external-call", "client_tool", "{}")));
        Agent agent = Agent.builder("external-null-result")
            .chatModel(model)
            .tool(Tool.builder("client_tool", "client tool")
                .executionTarget(ToolExecutionTarget.EXTERNAL).build())
            .build();
        AgentTurn waiting = new AgentRunner().run(agent, "run");
        try {
            new AgentRunner().submitResume(waiting, new AgentResumeCommand(
                AgentResumeCommandType.TOOL_RESULT, null, "external-call", null));
            fail("null external result content must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("content"));
        }
        assertEquals(AgentTurnStatus.WAITING_FOR_TOOL, waiting.getStatus());
    }

    @Test
    public void shouldRejectExpiredUserInputSuspensionWithoutMutatingTurn() throws Exception {
        Agent agent = Agent.builder("expired-input")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .build();
        AgentRunner runner = new AgentRunner();
        AgentTurn turn = AgentTurn.start(agent, "input");
        runner.suspend(turn, AgentSuspension.userInput("answer", 1));
        Thread.sleep(5);
        try {
            runner.submitResume(turn, AgentResumeCommand.userInput("answer"));
            fail("expired user input must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("user input has expired"));
        }
        assertEquals(AgentTurnStatus.WAITING_FOR_USER, turn.getStatus());
        assertTrue(turn.getPrompt().getMessages().size() <= 1);
    }

    @Test
    public void expiredSuspensionCanFailOrCancelTurnAccordingToPolicy() throws Exception {
        for (AgentSuspensionExpirationStrategy strategy : new AgentSuspensionExpirationStrategy[]{
            AgentSuspensionExpirationStrategy.FAIL_TURN,
            AgentSuspensionExpirationStrategy.CANCEL_TURN}) {
            AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
            model.enqueue(prompt -> toolCalls(new ToolCall("external", "client", "{}")));
            InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
            Agent agent = Agent.builder("expired-" + strategy)
                .chatModel(model)
                .tool(Tool.builder("client", "client")
                    .executionTarget(ToolExecutionTarget.EXTERNAL).build())
                .executionPolicy(AgentExecutionPolicy.builder()
                    .externalToolTimeoutMillis(1)
                    .suspensionExpirationStrategy(strategy).build())
                .build();
            List<AgentEvent> events = new ArrayList<>();
            AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent))
                .addEventListener(events::add);
            AgentTurn waiting = runner.run(agent, "run");
            Thread.sleep(10);
            AgentTurn terminal = runner.submitResume(waiting,
                AgentResumeCommand.toolResult("external", "late"));
            assertTrue(terminal.getStatus().isTerminal());
            assertEquals(strategy == AgentSuspensionExpirationStrategy.CANCEL_TURN
                ? AgentTurnStatus.CANCELLED : AgentTurnStatus.FAILED, terminal.getStatus());
            assertEquals(terminal.getStatus(), store.load(terminal.getId()).getState().getStatus());
            assertTrue(hasEvent(events, strategy == AgentSuspensionExpirationStrategy.CANCEL_TURN
                ? AgentEventType.TURN_CANCELLED : AgentEventType.TURN_FAILED));
            assertTrue("expired command must not append the late result",
                terminal.getPrompt().getMessages().stream()
                    .noneMatch(message -> "late".equals(message.getTextContent())));
        }
    }

    private static AgentEvent event(List<AgentEvent> events, AgentEventType type) {
        for (AgentEvent event : events) {
            if (event.getType() == type) return event;
        }
        throw new AssertionError("Event not found: " + type);
    }

    private static boolean hasEvent(List<AgentEvent> events, AgentEventType type) {
        for (AgentEvent event : events) {
            if (event.getType() == type) return true;
        }
        return false;
    }
}
