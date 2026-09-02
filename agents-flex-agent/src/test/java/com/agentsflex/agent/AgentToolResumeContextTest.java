/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.exception.AgentFormRequiredException;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.tool.AgentFormDefinition;
import com.agentsflex.agent.tool.AgentToolContext;
import com.agentsflex.agent.tool.AgentToolResumeInfo;
import com.agentsflex.agent.tool.AgentToolResumeType;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Tool;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * AgentToolContext 在各类挂起恢复和重试路径中的契约测试。
 */
public class AgentToolResumeContextTest {

    @Test
    public void resumeInfoNormalizesInvalidCoreValuesAndCopiesMetadata() {
        AgentToolResumeInfo none = new AgentToolResumeInfo(
            null, -1, 4, 99L, null, null, null);
        assertEquals(AgentToolResumeType.NONE, none.getType());
        assertFalse(none.isResumed());
        assertEquals(0, none.getRetryAttempt());
        assertEquals(0L, none.getRetryNextRunnableAt());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "approval-ui");
        AgentToolResumeInfo approval = new AgentToolResumeInfo(
            AgentToolResumeType.APPROVAL, -1, -4, -9L, metadata, null, null);

        assertEquals(0, approval.getResumeCount());
        assertEquals(0, approval.getRetryAttempt());
        assertEquals(0L, approval.getRetryNextRunnableAt());
        metadata.put("mutated", true);
        assertFalse(approval.getMetadata().containsKey("mutated"));
        try {
            approval.getMetadata().put("forbidden", true);
            fail("resume metadata must be immutable");
        } catch (UnsupportedOperationException expected) {
            // 只读契约
        }

        AgentToolResumeInfo retry = new AgentToolResumeInfo(
            AgentToolResumeType.RETRY, -1, -4, -9L, null, "java.io.IOException", "timeout");
        assertEquals(0, retry.getResumeCount());
        assertEquals(0, retry.getRetryAttempt());
        assertEquals(0L, retry.getRetryNextRunnableAt());
        assertEquals("java.io.IOException", retry.getPreviousErrorType());
        assertEquals("timeout", retry.getPreviousErrorMessage());
    }

    @Test
    public void approvalResumeIsFirstRealExecution() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        List<AgentToolContext> contexts = new ArrayList<>();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("approval-call", "danger", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("approval-context-agent")
            .chatModel(model)
            .tool(tool("danger", args -> {
                contexts.add(AgentToolContext.current());
                return "ok";
            }))
            .toolApprovalPolicy((turn, call, tool) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();

        AgentRunner runner = new AgentRunner(new InMemoryAgentTurnStore(),
            new com.agentsflex.agent.loader.InMemoryAgentLoader(agent));
        AgentTurn waiting = runner.run(agent, "run");
        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.approveTool("approval-call"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(1, contexts.size());
        AgentToolContext context = contexts.get(0);
        assertTrue(context.isResumed());
        assertFalse(context.isReplay());
        assertEquals(1, context.getExecutionAttempt());
        assertEquals(AgentToolResumeType.APPROVAL, context.getResumeInfo().getType());
        assertEquals(1, context.getResumeInfo().getResumeCount());
    }

    @Test
    public void formResumeCarriesAttemptAndSurvivesRunnerRestore() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        List<AgentToolContext> contexts = new ArrayList<>();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("form-call", "collect", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        AgentFormDefinition form = AgentFormDefinition.builder("details")
            .description("details")
            .schema(schema)
            .build();
        Agent agent = Agent.builder("form-context-agent")
            .chatModel(model)
            .tool(tool("collect", args -> {
                AgentToolContext context = AgentToolContext.current();
                contexts.add(context);
                if (context.getSubmittedFormData().isEmpty()) {
                    throw new AgentFormRequiredException(form);
                }
                return "accepted";
            }))
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner first = new AgentRunner(store,
            new com.agentsflex.agent.loader.InMemoryAgentLoader(agent));
        AgentTurn waiting = first.run(agent, "run");
        assertEquals(1, contexts.get(0).getExecutionAttempt());
        assertEquals(AgentToolResumeType.NONE, contexts.get(0).getResumeInfo().getType());

        AgentRunner second = new AgentRunner(store,
            new com.agentsflex.agent.loader.InMemoryAgentLoader(agent));
        AgentTurn completed = second.resume(waiting.getId(),
            AgentResumeCommand.userInput("form-call", mapOf("answer", "yes")));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(2, contexts.size());
        AgentToolContext resumed = contexts.get(1);
        assertTrue(resumed.isResumed());
        assertTrue(resumed.isReplay());
        assertEquals(2, resumed.getExecutionAttempt());
        assertEquals(AgentToolResumeType.FORM_INPUT, resumed.getResumeInfo().getType());
        assertEquals(1, resumed.getResumeInfo().getResumeCount());
        assertEquals("yes", resumed.getSubmittedFormData().get("answer"));
    }

    @Test
    public void formResumeCanCollectMultipleSubmissionsForOneToolCall() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        List<AgentToolContext> contexts = new ArrayList<>();
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        AgentFormDefinition form = AgentFormDefinition.builder("step-details")
            .description("step details").schema(schema).build();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("multi-form", "collect", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("multi-form-context-agent")
            .chatModel(model)
            .tool(tool("collect", args -> {
                AgentToolContext context = AgentToolContext.current();
                contexts.add(context);
                Map<String, Object> submitted = context.getSubmittedFormData();
                if (!submitted.containsKey("first") || !submitted.containsKey("second")) {
                    throw new AgentFormRequiredException(form);
                }
                return submitted.get("first") + ":" + submitted.get("second");
            }))
            .build();
        AgentRunner runner = new AgentRunner(new InMemoryAgentTurnStore(),
            new com.agentsflex.agent.loader.InMemoryAgentLoader(agent));

        AgentTurn waiting = runner.run(agent, "run");
        waiting = runner.resume(waiting,
            AgentResumeCommand.userInput("multi-form", mapOf("first", "one")));
        assertEquals(AgentTurnStatus.WAITING_FOR_USER, waiting.getStatus());
        assertEquals(2, contexts.size());
        assertEquals(2, contexts.get(1).getExecutionAttempt());
        assertEquals(AgentToolResumeType.FORM_INPUT, contexts.get(1).getResumeType());
        assertEquals("one", contexts.get(1).getSubmittedFormData().get("first"));

        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.userInput("multi-form", mapOf("second", "two")));
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(3, contexts.size());
        AgentToolContext finalContext = contexts.get(2);
        assertEquals(3, finalContext.getExecutionAttempt());
        assertEquals(2, finalContext.getResumeCount());
        assertEquals("one", finalContext.getSubmittedFormData().get("first"));
        assertEquals("two", finalContext.getSubmittedFormData().get("second"));
    }

    @Test
    public void retryResumeIncrementsAttemptAndIncludesPreviousError() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        List<AgentToolContext> contexts = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("retry-call", "unstable", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("retry-context-agent")
            .chatModel(model)
            .tool(tool("unstable", args -> {
                contexts.add(AgentToolContext.current());
                int invocation = calls.getAndIncrement();
                if (invocation < 2) {
                    throw new RuntimeException("temporary failure " + (invocation + 1));
                }
                return "ok";
            }))
            .executionPolicy(AgentExecutionPolicy.builder()
                .retryPolicy(AgentRetryPolicy.builder().maxRetries(2)
                    .initialDelayMillis(0).maxDelayMillis(0).build())
                .build())
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store,
            new com.agentsflex.agent.loader.InMemoryAgentLoader(agent));
        AgentTurn scheduled = runner.run(agent, "run");
        assertEquals(AgentTurnStatus.RETRY_SCHEDULED, scheduled.getStatus());

        AgentWorker worker = new AgentWorker("resume-worker", runner, 10000);
        AgentTurn secondScheduled = worker.pollAndRun(1).get(0);
        assertEquals(AgentTurnStatus.RETRY_SCHEDULED, secondScheduled.getStatus());
        AgentTurn completed = worker.pollAndRun(1).get(0);
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(3, contexts.size());
        AgentToolContext firstRetry = contexts.get(1);
        assertEquals(2, firstRetry.getExecutionAttempt());
        assertEquals(1, firstRetry.getResumeInfo().getResumeCount());
        assertEquals("temporary failure 1", firstRetry.getPreviousErrorMessage());
        assertEquals(1, firstRetry.getRetryAttempt());
        assertTrue(firstRetry.getRetryNextRunnableAt() > 0);
        assertFalse(firstRetry.getResumeInfo().getMetadata().containsKey("retryAttempt"));
        assertFalse(firstRetry.getResumeInfo().getMetadata().containsKey("nextRunnableAt"));
        AgentToolContext resumed = contexts.get(2);
        assertEquals(AgentToolResumeType.RETRY, resumed.getResumeInfo().getType());
        assertTrue(resumed.isResumed());
        assertTrue(resumed.isReplay());
        assertEquals(3, resumed.getExecutionAttempt());
        assertEquals(2, resumed.getResumeInfo().getResumeCount());
        assertEquals(RuntimeException.class.getName(), resumed.getPreviousErrorType());
        assertEquals("temporary failure 2", resumed.getPreviousErrorMessage());
        assertEquals(2, resumed.getRetryAttempt());
        assertEquals(2, resumed.getResumeInfo().getRetryAttempt());
        assertTrue(resumed.getRetryNextRunnableAt() > 0);
        assertFalse(resumed.getResumeInfo().getMetadata().containsKey("retryAttempt"));
    }

    @Test
    public void resumeInfoIsIsolatedPerToolCall() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        List<AgentToolContext> contexts = new ArrayList<>();
        Map<String, Integer> attempts = new LinkedHashMap<>();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("first", "collect", "{}"),
            new ToolCall("second", "plain", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        AgentFormDefinition form = AgentFormDefinition.builder("details")
            .description("details").schema(schema).build();
        Agent agent = Agent.builder("multi-context-agent")
            .chatModel(model)
            .tool(tool("collect", args -> {
                AgentToolContext context = AgentToolContext.current();
                contexts.add(context);
                if (context.getSubmittedFormData().isEmpty()) throw new AgentFormRequiredException(form);
                return "first-ok";
            }))
            .tool(tool("plain", args -> {
                contexts.add(AgentToolContext.current());
                return "second-ok";
            }))
            .build();
        AgentRunner runner = new AgentRunner(new InMemoryAgentTurnStore(),
            new com.agentsflex.agent.loader.InMemoryAgentLoader(agent));
        AgentTurn waiting = runner.run(agent, "run");
        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.userInput("first", mapOf("answer", "yes")));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(3, contexts.size());
        assertEquals("first", contexts.get(1).getToolCallId());
        assertEquals(AgentToolResumeType.FORM_INPUT, contexts.get(1).getResumeInfo().getType());
        assertEquals(2, contexts.get(1).getExecutionAttempt());
        assertEquals("second", contexts.get(2).getToolCallId());
        assertEquals(AgentToolResumeType.NONE, contexts.get(2).getResumeInfo().getType());
        assertEquals(1, contexts.get(2).getExecutionAttempt());
    }

    private static Tool tool(String name, java.util.function.Function<Map<String, Object>, Object> function) {
        return AgentScenarioTestSupport.tool(name, function);
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }
}
