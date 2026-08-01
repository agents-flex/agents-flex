/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentRuntimeEvent;
import com.agentsflex.agent.event.AgentRuntimeEventType;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.AgentRunVersionConflictException;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.agent.tool.ToolErrorStrategy;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.Prompt;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.agent.AgentScenarioTestSupport.response;
import static com.agentsflex.agent.AgentScenarioTestSupport.tool;
import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 流式失败、执行中取消、审批恢复和并发恢复边界测试。 */
public class AgentExecutionBoundaryContractTest {

    @Test
    public void shouldPublishPartialDeltaBeforeStreamingFailure() {
        List<AgentRuntimeEvent> events = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void chatStream(Prompt prompt, StreamResponseListener listener,
                                   ChatOptions options) {
                AiMessage delta = new AiMessage("partial");
                delta.setFinished(false);
                listener.onMessage(null, response(prompt, delta));
                listener.onError(null, new RuntimeException("stream interrupted"));
            }
        };
        AgentRunner runner = new AgentRunner().addRuntimeEventListener(events::add);

        AgentRun run = runner.run(Agent.builder("partial-stream")
                .chatModel(model).build(), "input",
            AgentRunOptions.builder().invocationContext(AgentInvocationContext.builder()
                .streaming(true).build()).build());

        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertTrue(run.getError().getMessage().contains("stream interrupted"));
        assertTrue(hasEventWithContent(events, AgentRuntimeEventType.MODEL_TEXT_DELTA,
            "partial"));
        assertEquals(1, countTerminalEvents(events));
    }

    @Test
    public void shouldCancelAfterRunningToolReturnsWithoutCallingModelAgain() throws Exception {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("slow-1", "slow", "{}")));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Agent agent = Agent.builder("cooperative-cancel")
            .chatModel(model)
            .tool(tool("slow", args -> {
                entered.countDown();
                await(release);
                return "done";
            }))
            .build();
        AgentRunner runner = new AgentRunner(
            new InMemoryAgentRunStore(), new InMemoryAgentLoader(agent));
        AgentRun started = runner.start(agent, "input");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<AgentRun> result = executor.submit(() -> runner.run(started));
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        runner.requestCancellation(started.getId());
        release.countDown();
        AgentRun cancelled = result.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(AgentRunStatus.CANCELLED, cancelled.getStatus());
        assertEquals(1, model.getCallCount());
    }

    @Test
    public void shouldResumeMultipleToolsInOriginalOrderAfterApproval() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(
            new ToolCall("first-1", "first", "{}"),
            new ToolCall("second-1", "second", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        List<String> executions = new ArrayList<>();
        Agent agent = Agent.builder("multi-tool-approval")
            .chatModel(model)
            .tool(tool("first", args -> { executions.add("first"); return "one"; }))
            .tool(tool("second", args -> { executions.add("second"); return "two"; }))
            .toolApprovalPolicy((run, call, value) -> "first".equals(call.getName())
                ? ToolApprovalDecision.REQUIRE_APPROVAL : ToolApprovalDecision.ALLOW)
            .build();
        AgentRunner runner = new AgentRunner(
            new InMemoryAgentRunStore(), new InMemoryAgentLoader(agent));

        AgentRun waiting = runner.run(agent, "input");
        AgentRun completed = runner.resume(waiting,
            AgentResumeCommand.approveTool("first-1"));

        assertEquals(Arrays.asList("first", "second"), executions);
        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals(2, toolMessages(completed).size());
        assertEquals("first-1", toolMessages(completed).get(0).getToolCallId());
        assertEquals("second-1", toolMessages(completed).get(1).getToolCallId());
    }

    @Test
    public void shouldReturnStructuredDirectDenialWithoutExecutingTool() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("delete-1", "delete", "{}")));
        model.enqueue(prompt -> new AiMessage("denied handled"));
        AtomicInteger executions = new AtomicInteger();
        Agent agent = Agent.builder("direct-denial")
            .chatModel(model)
            .tool(tool("delete", args -> executions.incrementAndGet()))
            .toolApprovalPolicy((run, call, value) -> ToolApprovalDecision.deny()
                .code("POLICY_DENY").reason("protected resource")
                .metadata("rule", "R-7").build())
            .build();

        AgentRun completed = new AgentRunner().run(agent, "delete");

        assertEquals(0, executions.get());
        ToolMessage denied = toolMessages(completed).get(0);
        assertTrue(denied.getContent().contains("POLICY_DENY"));
        assertTrue(denied.getContent().contains("protected resource"));
        assertTrue(denied.getContent().contains("R-7"));
    }

    @Test
    public void shouldConvertMalformedToolArgumentsToErrorMessage() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("bad-1", "lookup", "{invalid")));
        model.enqueue(prompt -> new AiMessage("recovered"));
        Agent agent = Agent.builder("malformed-arguments")
            .chatModel(model)
            .tool(tool("lookup", args -> "unused"))
            .executionPolicy(AgentExecutionPolicy.builder()
                .toolErrorStrategy(ToolErrorStrategy.RETURN_ERROR_TO_MODEL)
                .build())
            .build();

        AgentRun completed = new AgentRunner().run(agent, "input");

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertTrue(toolMessages(completed).get(0).getContent().contains("error"));
    }

    @Test
    public void shouldFailAfterModelRetryAttemptsAreExhausted() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> { throw new RuntimeException("failure-1"); });
        model.enqueue(prompt -> { throw new RuntimeException("failure-2"); });
        model.enqueue(prompt -> { throw new RuntimeException("failure-3"); });
        Agent agent = Agent.builder("retry-exhaustion")
            .chatModel(model)
            .executionPolicy(AgentExecutionPolicy.builder()
                .retryPolicy(AgentRetryPolicy.builder().maxRetries(2)
                    .initialDelayMillis(0).maxDelayMillis(0).build())
                .build())
            .build();
        AgentRunner runner = new AgentRunner(
            new InMemoryAgentRunStore(), new InMemoryAgentLoader(agent));

        AgentRun run = runner.run(agent, "input");
        new AgentWorker("retry-worker", runner, 1000).pollAndRun(1);
        List<AgentRun> finalAttempt = new AgentWorker("retry-worker", runner, 1000)
            .pollAndRun(1);

        assertEquals(AgentRunStatus.RETRY_SCHEDULED, run.getStatus());
        assertEquals(1, finalAttempt.size());
        assertEquals(AgentRunStatus.FAILED, finalAttempt.get(0).getStatus());
        assertEquals(3, model.getCallCount());
        assertEquals(2, finalAttempt.get(0).getRetryCount());
    }

    @Test
    public void shouldFailCleanlyWhenModelReturnsNoResponse() {
        ChatModel model = new ChatModel() {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) { return null; }
            @Override
            public void chatStream(Prompt prompt, StreamResponseListener listener,
                                   ChatOptions options) { throw new UnsupportedOperationException(); }
        };

        AgentRun run = new AgentRunner().run(Agent.builder("null-response")
            .chatModel(model).build(), "input");

        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertTrue(run.getError().getMessage().contains("null response"));
    }

    @Test
    public void shouldAllowOnlyOneConcurrentResumeToCheckpoint() throws Exception {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        Agent agent = Agent.builder("concurrent-resume")
            .chatModel(new AgentScenarioTestSupport.QueueChatModel()).build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner creator = new AgentRunner(store, registry);
        AgentRun waiting = creator.start(agent, "input");
        creator.suspend(waiting, AgentSuspension.userInput("value"));
        AgentRunner firstRunner = new AgentRunner(store, registry);
        AgentRunner secondRunner = new AgentRunner(store, registry);
        AgentRun first = firstRunner.restore(waiting.getId());
        AgentRun second = secondRunner.restore(waiting.getId());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(() -> resume(firstRunner, first, start, success, conflicts));
        executor.submit(() -> resume(secondRunner, second, start, success, conflicts));
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, success.get());
        assertEquals(1, conflicts.get());
        assertEquals(AgentRunStatus.RUNNING, creator.restore(waiting.getId()).getStatus());
    }

    private void resume(AgentRunner runner, AgentRun run, CountDownLatch start,
                        AtomicInteger success, AtomicInteger conflicts) {
        await(start);
        try {
            runner.submitResume(run, AgentResumeCommand.userInput("answer"));
            success.incrementAndGet();
        } catch (AgentRunVersionConflictException expected) {
            conflicts.incrementAndGet();
        }
    }

    private boolean hasEventWithContent(List<AgentRuntimeEvent> events,
                                        AgentRuntimeEventType type, String content) {
        for (AgentRuntimeEvent event : events) {
            if (event.getType() == type && content.equals(event.getData().get("content"))) {
                return true;
            }
        }
        return false;
    }

    private int countTerminalEvents(List<AgentRuntimeEvent> events) {
        int result = 0;
        for (AgentRuntimeEvent event : events) {
            if (event.getType() == AgentRuntimeEventType.RUN_COMPLETED
                || event.getType() == AgentRuntimeEventType.RUN_FAILED
                || event.getType() == AgentRuntimeEventType.RUN_CANCELLED
                || event.getType() == AgentRuntimeEventType.BUDGET_EXCEEDED) result++;
        }
        return result;
    }

    private List<ToolMessage> toolMessages(AgentRun run) {
        List<ToolMessage> result = new ArrayList<>();
        for (Message message : run.getPrompt().getMemory().getMessages(Integer.MAX_VALUE)) {
            if (message instanceof ToolMessage) result.add((ToolMessage) message);
        }
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
