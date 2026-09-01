/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.AgentTurnStore;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.prompt.Prompt;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AgentAdvancedFeaturesTest {

    @Test
    public void shouldApproveToolBeforeExecution() {
        QueueChatModel model = new QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> aiWithCalls(new ToolCall("danger-1", "danger", "{}")));
        model.enqueue(prompt -> new AiMessage("approved"));
        Agent agent = Agent.builder("approval-agent")
            .chatModel(model)
            .tool(tool("danger", args -> executions.incrementAndGet()))
            .toolApprovalPolicy((turn, call, tool) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        AgentRunner runner = new AgentRunner(
            new InMemoryAgentTurnStore(), new InMemoryAgentLoader(agent));

        AgentTurn waiting = runner.run(agent, "execute");
        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        assertEquals(0, executions.get());
        assertEquals("danger-1", waiting.getSuspension().getCorrelationId());

        AgentTurn completed = runner.resume(waiting.getId(),
            AgentResumeCommand.approveTool("danger-1"));
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(1, executions.get());
    }

    @Test
    public void shouldReturnRejectedToolResultToModel() {
        QueueChatModel model = new QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> aiWithCalls(new ToolCall("danger-2", "danger", "{}")));
        model.enqueue(prompt -> {
            Message last = prompt.getMessages().get(prompt.getMessages().size() - 1);
            assertTrue(last instanceof ToolMessage);
            assertTrue(last.getTextContent().contains("tool_rejected"));
            assertTrue(last.getTextContent().contains("not allowed"));
            return new AiMessage("rejected safely");
        });
        Agent agent = Agent.builder("rejection-agent")
            .chatModel(model)
            .tool(tool("danger", args -> executions.incrementAndGet()))
            .toolApprovalPolicy((turn, call, tool) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        AgentRunner runner = new AgentRunner();

        AgentTurn waiting = runner.run(agent, "execute");
        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.rejectTool("danger-2", "not allowed"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals("rejected safely", completed.getFinalOutput());
        assertEquals(0, executions.get());
    }

    @Test
    public void shouldRetryDueRunThroughWorkerLease() {
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> { throw new RuntimeException("temporary"); });
        model.enqueue(prompt -> new AiMessage("recovered"));
        Agent agent = Agent.builder("retry-agent")
            .chatModel(model)
            .executionPolicy(AgentExecutionPolicy.builder()
                .retryPolicy(AgentRetryPolicy.builder()
                    .maxRetries(2)
                    .initialDelayMillis(0)
                    .maxDelayMillis(0)
                    .build())
                .build())
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));

        AgentTurn scheduled = runner.run(agent, "retry");
        assertEquals(AgentTurnStatus.RETRY_SCHEDULED, scheduled.getStatus());
        assertEquals(1, scheduled.getRetryCount());

        List<AgentTurn> processed = new AgentWorker("worker-a", runner, 10000).pollAndRun(1);
        assertEquals(1, processed.size());
        assertEquals(AgentTurnStatus.COMPLETED, processed.get(0).getStatus());
        assertEquals("recovered", processed.get(0).getFinalOutput());
    }

    @Test
    public void shouldClaimRunWithOnlyOneWorker() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader());
        Agent agent = Agent.builder("lease-agent").chatModel(new QueueChatModel()).build();
        AgentTurn turn = runner.start(agent, "lease");
        long now = System.currentTimeMillis();

        List<AgentTurnSnapshot> first = store.claimRunnable("worker-a", now, 10000, 1);
        List<AgentTurnSnapshot> second = store.claimRunnable("worker-b", now, 10000, 1);

        assertEquals(1, first.size());
        assertTrue(second.isEmpty());
        assertEquals(turn.getId(), first.get(0).getState().getTurnId());
        store.releaseLease(turn.getId(), "worker-a", first.get(0).getState().getLeaseId());
        assertEquals(1, store.claimRunnable("worker-b", now, 10000, 1).size());
    }

    @Test
    public void shouldRejectExecutionOutsideActiveLease() {
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> new AiMessage("must not run"));
        Agent agent = Agent.builder("leased-agent").chatModel(model).build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner runner = new AgentRunner(store, registry);
        AgentTurn turn = runner.start(agent, "lease");
        store.claimRunnable("worker-a", System.currentTimeMillis(), 10000, 1);

        try {
            runner.runUntilBlocked(turn.getId());
            fail("Expected active lease validation");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("worker-a"));
        }
        assertEquals(0, model.getCallCount());
    }

    @Test
    public void shouldStopWhenTokenBudgetIsExceeded() {
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> {
            AiMessage message = new AiMessage("large response");
            message.setTotalTokens(11);
            return message;
        });
        Agent agent = Agent.builder("budget-agent")
            .chatModel(model)
            .executionPolicy(AgentExecutionPolicy.builder()
                .budget(AgentBudget.builder().maxTotalTokens(10).build())
                .build())
            .build();

        AgentTurn turn = new AgentRunner().run(agent, "budget");

        assertEquals(AgentTurnStatus.BUDGET_EXCEEDED, turn.getStatus());
        assertTrue(turn.getBudgetExceededReason().startsWith("maxTotalTokens (used="));
    }

    @Test
    public void shouldStopWhenWallClockBudgetIsExceeded() {
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(error);
            }
            return new AiMessage("late response");
        });
        Agent agent = Agent.builder("time-budget-agent")
            .chatModel(model)
            .executionPolicy(AgentExecutionPolicy.builder()
                .budget(AgentBudget.builder().maxDurationMillis(1).build())
                .build())
            .build();

        AgentTurn turn = new AgentRunner().run(agent, "budget");

        assertEquals(AgentTurnStatus.BUDGET_EXCEEDED, turn.getStatus());
        assertTrue(turn.getBudgetExceededReason().startsWith("maxDurationMillis (elapsed="));
    }

    @Test
    public void shouldStopBeforeToolCallBeyondBudget() {
        QueueChatModel model = new QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> aiWithCalls(
            new ToolCall("one", "work", "{}"),
            new ToolCall("two", "work", "{}")));
        Agent agent = Agent.builder("tool-budget-agent")
            .chatModel(model)
            .tool(tool("work", args -> executions.incrementAndGet()))
            .executionPolicy(AgentExecutionPolicy.builder()
                .budget(AgentBudget.builder().maxToolCalls(1).build())
                .build())
            .build();

        AgentTurn turn = new AgentRunner().run(agent, "budget");

        assertEquals(AgentTurnStatus.BUDGET_EXCEEDED, turn.getStatus());
        assertTrue(turn.getBudgetExceededReason().startsWith("maxToolCalls (used="));
        assertEquals(1, executions.get());
    }

    @Test
    public void shouldAutomaticallyPollRunnableRuns() throws Exception {
        QueueChatModel model = new QueueChatModel();
        model.enqueue(prompt -> new AiMessage("background complete"));
        Agent agent = Agent.builder("background-agent").chatModel(model).build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        CountDownLatch completed = new CountDownLatch(1);
        runner.addEventListener(event -> {
            if (event.getType() == AgentEventType.TURN_COMPLETED) completed.countDown();
        });
        AgentTurn scheduled = runner.start(agent, "background");

        AgentWorker worker = new AgentWorker("background-worker", runner, 10000);
        try {
            worker.startPolling(5, 1);
            assertTrue(completed.await(2, TimeUnit.SECONDS));
        } finally {
            worker.close();
        }

        assertEquals(AgentTurnStatus.COMPLETED,
            runner.restore(scheduled.getId()).getStatus());
    }

    @Test
    public void shouldResolvePendingToolInAnotherRunner() {
        InMemoryAgentTurnStore durableStore = new InMemoryAgentTurnStore();
        CrashOnPendingStore crashingStore = new CrashOnPendingStore(durableStore);
        QueueChatModel model = new QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> aiWithCalls(new ToolCall("remote-1", "remote", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("remote-agent")
            .chatModel(model)
            .tool(tool("remote", args -> executions.incrementAndGet()))
            .build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner first = new AgentRunner(crashingStore, registry);
        AgentTurn turn = first.start(agent, "remote");
        try {
            first.step(turn);
            fail("Expected simulated crash");
        } catch (SimulatedCrash expected) {
            // pending ToolCall 已写入持久化 Store。
        }

        AgentRunner second = new AgentRunner(durableStore, registry);
        AgentTurn restored = second.runUntilBlocked(turn.getId());

        assertEquals(AgentTurnStatus.COMPLETED, restored.getStatus());
        assertEquals(1, executions.get());
        assertEquals(2, model.getCallCount());
    }

    @Test
    public void shouldNotRepeatCompletedToolAfterCrash() {
        InMemoryAgentTurnStore durableStore = new InMemoryAgentTurnStore();
        CrashAfterFirstToolStore crashingStore = new CrashAfterFirstToolStore(durableStore);
        QueueChatModel model = new QueueChatModel();
        AtomicInteger firstExecutions = new AtomicInteger();
        AtomicInteger secondExecutions = new AtomicInteger();
        model.enqueue(prompt -> aiWithCalls(
            new ToolCall("first-1", "first", "{}"),
            new ToolCall("second-1", "second", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("partial-agent")
            .chatModel(model)
            .tool(tool("first", args -> firstExecutions.incrementAndGet()))
            .tool(tool("second", args -> secondExecutions.incrementAndGet()))
            .build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner first = new AgentRunner(crashingStore, registry);
        AgentTurn turn = first.start(agent, "tools");
        try {
            first.step(turn);
            fail("Expected simulated crash");
        } catch (SimulatedCrash expected) {
            // 第一个工具结果已保存，第二个工具仍处于 pending 状态。
        }

        AgentRunner second = new AgentRunner(durableStore, registry);
        AgentTurn completed = second.runUntilBlocked(turn.getId());

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(1, firstExecutions.get());
        assertEquals(1, secondExecutions.get());
    }

    private static Tool tool(String name, Function<Map<String, Object>, Object> function) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Parameter[] getParameters() { return new Parameter[0]; }
            @Override public Object invoke(Map<String, Object> argsMap) { return function.apply(argsMap); }
        };
    }

    private static AiMessage aiWithCalls(ToolCall... calls) {
        AiMessage message = new AiMessage();
        message.setToolCalls(Arrays.asList(calls));
        return message;
    }

    private static AiMessageResponse response(Prompt prompt, AiMessage message) {
        ChatContext context = new ChatContext();
        context.setPrompt(prompt);
        return new AiMessageResponse(context, null, message);
    }

    private static final class QueueChatModel implements ChatModel {
        private final Deque<Function<Prompt, AiMessage>> responses = new ArrayDeque<>();
        private int callCount;

        void enqueue(Function<Prompt, AiMessage> value) { responses.add(value); }
        int getCallCount() { return callCount; }

        @Override
        public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
            callCount++;
            assertFalse("No queued response for model call " + callCount, responses.isEmpty());
            return response(prompt, responses.removeFirst().apply(prompt));
        }

        @Override
        public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CrashOnPendingStore implements AgentTurnStore {
        private final AgentTurnStore delegate;
        private boolean crashed;

        private CrashOnPendingStore(AgentTurnStore delegate) { this.delegate = delegate; }
        @Override public AgentTurnSnapshot load(String turnId) { return delegate.load(turnId); }
        @Override public AgentTurnSnapshot findActiveTurn(String conversationId) {
            return delegate.findActiveTurn(conversationId);
        }
        @Override public long currentTimeMillis() { return delegate.currentTimeMillis(); }
        @Override public boolean requestCancellation(String turnId) {
            return delegate.requestCancellation(turnId);
        }
        @Override public java.util.List<AgentTurnSnapshot> claimRunnable(
            String workerId, long now, long leaseMillis, int limit) {
            return delegate.claimRunnable(workerId, now, leaseMillis, limit);
        }
        @Override public AgentTurnSnapshot renewLease(String turnId, String workerId,
                                                       String leaseId, long now, long leaseUntil) {
            return delegate.renewLease(turnId, workerId, leaseId, now, leaseUntil);
        }
        @Override public void releaseLease(String turnId, String workerId, String leaseId) {
            delegate.releaseLease(turnId, workerId, leaseId);
        }

        @Override
        public AgentTurnSnapshot save(AgentTurnSnapshot snapshot, long expectedVersion) {
            AgentTurnSnapshot saved = delegate.save(snapshot, expectedVersion);
            if (!crashed && AgentTurnExecutionPoint.PROCESS_TOOLS.equals(saved.getState().getExecutionPoint())
                && !saved.getState().getPendingToolCalls().isEmpty()) {
                crashed = true;
                throw new SimulatedCrash();
            }
            return saved;
        }
    }

    private static final class CrashAfterFirstToolStore implements AgentTurnStore {
        private final AgentTurnStore delegate;
        private boolean crashed;

        private CrashAfterFirstToolStore(AgentTurnStore delegate) { this.delegate = delegate; }
        @Override public AgentTurnSnapshot load(String turnId) { return delegate.load(turnId); }
        @Override public AgentTurnSnapshot findActiveTurn(String conversationId) {
            return delegate.findActiveTurn(conversationId);
        }
        @Override public long currentTimeMillis() { return delegate.currentTimeMillis(); }
        @Override public boolean requestCancellation(String turnId) {
            return delegate.requestCancellation(turnId);
        }
        @Override public java.util.List<AgentTurnSnapshot> claimRunnable(
            String workerId, long now, long leaseMillis, int limit) {
            return delegate.claimRunnable(workerId, now, leaseMillis, limit);
        }
        @Override public AgentTurnSnapshot renewLease(String turnId, String workerId,
                                                       String leaseId, long now, long leaseUntil) {
            return delegate.renewLease(turnId, workerId, leaseId, now, leaseUntil);
        }
        @Override public void releaseLease(String turnId, String workerId, String leaseId) {
            delegate.releaseLease(turnId, workerId, leaseId);
        }

        @Override
        public AgentTurnSnapshot save(AgentTurnSnapshot snapshot, long expectedVersion) {
            AgentTurnSnapshot saved = delegate.save(snapshot, expectedVersion);
            if (!crashed && saved.getState().getPendingToolCalls().size() == 1
                && countToolMessages(saved.getState().getMessages()) == 1) {
                crashed = true;
                throw new SimulatedCrash();
            }
            return saved;
        }

        private int countToolMessages(List<Message> messages) {
            int count = 0;
            for (Message message : messages) {
                if (message instanceof ToolMessage) { count++; }
            }
            return count;
        }
    }

    private static final class SimulatedCrash extends RuntimeException { }
}
