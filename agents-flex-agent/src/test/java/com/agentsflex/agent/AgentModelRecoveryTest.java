/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.message.AgentActionMessage;
import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.store.FastjsonAgentStoreSerializer;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.memory.DefaultChatMemory;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.exception.ModelException;
import com.agentsflex.core.model.exception.ModelOverloadedException;
import com.agentsflex.core.model.exception.ModelQuotaExceededException;
import com.agentsflex.core.model.exception.ModelRateLimitException;
import com.agentsflex.core.model.exception.TokenLimitExceededException;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolExecutionTarget;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.agent.AgentScenarioTestSupport.tool;
import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * WAITING_FOR_MODEL 和阻塞 Turn 接收新消息的端到端契约。
 */
public class AgentModelRecoveryTest {

    @Test
    public void shouldSuspendNormalizedModelFailuresWithStructuredDetails() {
        assertModelFailure(new ModelQuotaExceededException(
                "no credits", 429, "credit_balance_exhausted", "insufficient_quota"),
            AgentModelFailureType.QUOTA_EXCEEDED, 429, "credit_balance_exhausted");
        assertModelFailure(new TokenLimitExceededException(
                "context too long", 400, "context_length_exceeded", "invalid_request",
                TokenLimitExceededException.Phase.INPUT_CONTEXT),
            AgentModelFailureType.TOKEN_LIMIT_EXCEEDED, 400, "context_length_exceeded");
        assertModelFailure(new ModelOverloadedException(
                "busy", 503, "overloaded", "server_error"),
            AgentModelFailureType.OVERLOADED, 503, "overloaded");
        assertModelFailure(new RuntimeException("middleware wrapper",
                new ModelException("model endpoint unavailable")),
            AgentModelFailureType.UNAVAILABLE, 0, null);
        assertModelFailure(new ModelException("generic wrapper", rateLimited()),
            AgentModelFailureType.RATE_LIMITED, 429, "rate_limit");
    }

    @Test
    public void shouldWaitForModelAfterAutomaticRateLimitRetriesAreExhausted() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            throw rateLimited();
        });
        model.enqueue(prompt -> {
            throw rateLimited();
        });
        Agent agent = Agent.builder("rate-limit-recovery")
            .chatModel(model)
            .executionPolicy(AgentExecutionPolicy.builder()
                .retryPolicy(AgentRetryPolicy.builder().maxRetries(1)
                    .initialDelayMillis(0).maxDelayMillis(0).build())
                .build())
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));

        AgentTurn scheduled = runner.run(agent, "hello");
        List<AgentTurn> processed = new AgentWorker("model-retry", runner, 1000)
            .pollAndRun(1);

        assertEquals(AgentTurnStatus.RETRY_SCHEDULED, scheduled.getStatus());
        assertEquals(1, processed.size());
        assertEquals(AgentTurnStatus.WAITING_FOR_MODEL, processed.get(0).getStatus());
        assertEquals(AgentModelFailureType.RATE_LIMITED,
            processed.get(0).getModelFailure().getType());
        assertEquals(Long.valueOf(2500L),
            processed.get(0).getModelFailure().getRetryAfterMillis());
    }

    @Test
    public void shouldPublishStructuredModelFailureInSuspensionEvent() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            throw new ModelQuotaExceededException(
                "quota", 429, "insufficient_quota", "insufficient_quota");
        });
        List<AgentEvent> events = new ArrayList<>();

        AgentTurn waiting = new AgentRunner().addEventListener(events::add).run(
            Agent.builder("model-failure-event").chatModel(model).build(), "hello");

        AgentEvent suspended = null;
        for (AgentEvent event : events) {
            if (event.getType() == AgentEventType.TURN_SUSPENDED) suspended = event;
        }
        assertNotNull(suspended);
        Object value = suspended.getData().get("modelFailure");
        assertTrue(value instanceof Map);
        Map<?, ?> failure = (Map<?, ?>) value;
        assertEquals(waiting.getModelFailure().getFailureId(), failure.get("failureId"));
        assertEquals(AgentModelFailureType.QUOTA_EXCEEDED, failure.get("type"));
        assertEquals(429, failure.get("httpStatus"));
    }

    @Test
    public void shouldRetrySameTurnAfterModelConditionIsFixed() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            throw new ModelQuotaExceededException(
                "quota", 429, "insufficient_quota", "insufficient_quota");
        });
        model.enqueue(prompt -> new AiMessage("recovered"));
        Agent agent = Agent.builder("manual-model-recovery").chatModel(model).build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));

        AgentTurn waiting = runner.run(agent, "hello");
        String failureId = waiting.getModelFailure().getFailureId();
        AgentTurn completed = runner.resume(waiting.getId(),
            AgentResumeCommand.retryModel(failureId));

        assertEquals(waiting.getId(), completed.getId());
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals("recovered", completed.getFinalOutput());
        assertEquals(2, model.getCallCount());
        assertEquals(null, completed.getModelFailure());
    }

    @Test
    public void shouldRejectStaleModelFailureCorrelationId() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            throw new ModelQuotaExceededException(
                "quota", 429, "insufficient_quota", "insufficient_quota");
        });
        Agent agent = Agent.builder("stale-model-recovery").chatModel(model).build();
        AgentRunner runner = new AgentRunner();
        AgentTurn waiting = runner.run(agent, "hello");

        try {
            runner.resume(waiting, AgentResumeCommand.retryModel("stale-failure"));
            fail("stale model recovery command must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("correlationId"));
        }
        assertEquals(AgentTurnStatus.WAITING_FOR_MODEL, waiting.getStatus());
    }

    @Test
    public void shouldKeepCompletedToolsAndContinueSameTurnWithNewConversationMessage() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        ToolCall[] calls = new ToolCall[10];
        for (int index = 0; index < calls.length; index++) {
            calls[index] = new ToolCall("lookup-" + index, "lookup", "{}");
        }
        model.enqueue(prompt -> toolCalls(calls));
        model.enqueue(prompt -> {
            throw new ModelQuotaExceededException(
                "quota", 429, "credit_balance_exhausted", "insufficient_quota");
        });
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            int toolResults = 0;
            for (Message message : messages) {
                if (message instanceof ToolMessage) toolResults++;
            }
            assertEquals(10, toolResults);
            assertTrue(messages.get(messages.size() - 1) instanceof UserMessage);
            assertEquals("继续", messages.get(messages.size() - 1).getTextContent());
            return new AiMessage("continued");
        });
        Agent agent = Agent.builder("context-preserving-recovery")
            .chatModel(model)
            .tool(tool("lookup", args -> "result-" + executions.incrementAndGet()))
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        DefaultChatMemory memory = new DefaultChatMemory("model-conversation");
        AgentRunner runner = AgentRunner.builder()
            .turnStore(store)
            .agentLoader(new InMemoryAgentLoader(agent))
            .chatMemoryProvider(id -> memory)
            .build();

        AgentTurn waiting = runner.run(agent, "model-conversation", "执行十次查询");
        AgentTurn completed = runner.run(agent, "model-conversation", "继续");

        assertEquals(AgentTurnStatus.WAITING_FOR_MODEL, waiting.getStatus());
        assertEquals(waiting.getId(), completed.getId());
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(10, executions.get());
        assertEquals("continued", completed.getFinalOutput());
        assertTrue(completed.getPendingToolCalls().isEmpty());
    }

    @Test
    public void shouldSubmitMessageWithoutExecutingAndLetWorkerContinueSameTurn() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            throw new ModelQuotaExceededException(
                "quota", 429, "insufficient_quota", "insufficient_quota");
        });
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertEquals("继续", messages.get(messages.size() - 1).getTextContent());
            return new AiMessage("worker continued");
        });
        Agent agent = Agent.builder("async-model-recovery").chatModel(model).build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = AgentRunner.builder()
            .turnStore(store)
            .agentLoader(new InMemoryAgentLoader(agent))
            .chatMemoryProvider(id -> new DefaultChatMemory(id))
            .build();

        AgentTurn waiting = runner.run(agent, "async-model-recovery", "hello");
        AgentTurn runnable = runner.submitMessage(
            agent, "async-model-recovery", "继续");

        assertEquals(waiting.getId(), runnable.getId());
        assertEquals(AgentTurnStatus.RUNNING, runnable.getStatus());
        assertEquals(1, model.getCallCount());

        List<AgentTurn> processed = new AgentWorker("async-model-worker", runner, 1000)
            .pollAndRun(1);

        assertEquals(1, processed.size());
        assertEquals(AgentTurnStatus.COMPLETED, processed.get(0).getStatus());
        assertEquals("worker continued", processed.get(0).getFinalOutput());
        assertEquals(2, model.getCallCount());
    }

    @Test
    public void shouldClosePendingToolProtocolWhenMessageInterruptsApproval() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> toolCalls(new ToolCall("deploy-1", "deploy", "{}")));
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertTrue(messages.get(messages.size() - 2) instanceof ToolMessage);
            ToolMessage interrupted = (ToolMessage) messages.get(messages.size() - 2);
            assertEquals("deploy-1", interrupted.getToolCallId());
            assertTrue(interrupted.getContent().contains("superseded"));
            assertTrue(messages.get(messages.size() - 1) instanceof UserMessage);
            assertEquals("先不要发布，改为检查状态",
                messages.get(messages.size() - 1).getTextContent());
            return new AiMessage("已改为检查状态");
        });
        Agent agent = Agent.builder("approval-interruption")
            .chatModel(model)
            .tool(tool("deploy", args -> executions.incrementAndGet()))
            .toolApprovalPolicy((turn, call, value) ->
                ToolApprovalDecision.requireApproval().message("需要审批").build())
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        DefaultChatMemory memory = new DefaultChatMemory("approval-interruption");
        AgentRunner runner = AgentRunner.builder()
            .turnStore(store)
            .agentLoader(new InMemoryAgentLoader(agent))
            .chatMemoryProvider(id -> memory)
            .build();

        AgentTurn waiting = runner.run(agent, "approval-interruption", "发布");
        AgentTurn completed = runner.run(agent, "approval-interruption",
            "先不要发布，改为检查状态");

        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        assertEquals(waiting.getId(), completed.getId());
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(0, executions.get());
        assertTrue(completed.getPendingToolCalls().isEmpty());
        AgentActionMessage action = approvalAction(memory);
        assertEquals(AgentActionMessage.Status.CANCELLED, action.getStatus());
        assertFalse(action.getActions().contains("APPROVE"));
    }

    @Test
    public void shouldCancelExternalToolWaitAndRejectLateResult() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("browser-1", "browser", "{}")));
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertTrue(messages.get(messages.size() - 2) instanceof ToolMessage);
            assertEquals("browser-1",
                ((ToolMessage) messages.get(messages.size() - 2)).getToolCallId());
            assertEquals("不用浏览器了", messages.get(messages.size() - 1).getTextContent());
            return new AiMessage("已停止等待浏览器");
        });
        Tool external = Tool.builder("browser", "browser")
            .executionTarget(ToolExecutionTarget.EXTERNAL)
            .build();
        Agent agent = Agent.builder("external-interruption")
            .chatModel(model).tool(external).build();
        AgentRunner runner = new AgentRunner();

        AgentTurn waiting = runner.run(agent, "打开浏览器");
        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.userMessage("不用浏览器了"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertTrue(completed.getPendingToolCalls().isEmpty());
        try {
            runner.resume(completed, AgentResumeCommand.toolResult("browser-1", "late"));
            fail("late external result must not resume an interrupted turn");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not blocked"));
        }
    }

    @Test
    public void shouldCancelScheduledToolRetryWithoutRepeatingSideEffect() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger attempts = new AtomicInteger();
        model.enqueue(prompt -> toolCalls(new ToolCall("unstable-1", "unstable", "{}")));
        model.enqueue(prompt -> {
            assertTrue(prompt.getMessages().get(prompt.getMessages().size() - 2)
                instanceof ToolMessage);
            return new AiMessage("replanned");
        });
        Agent agent = Agent.builder("retry-interruption")
            .chatModel(model)
            .tool(tool("unstable", args -> {
                attempts.incrementAndGet();
                throw new RuntimeException("temporary");
            }))
            .executionPolicy(AgentExecutionPolicy.builder()
                .retryPolicy(AgentRetryPolicy.builder().maxRetries(1)
                    .initialDelayMillis(60000).maxDelayMillis(60000).build())
                .build())
            .build();
        AgentRunner runner = new AgentRunner();

        AgentTurn waiting = runner.run(agent, "execute");
        assertEquals(AgentTurnStatus.RETRY_SCHEDULED, waiting.getStatus());
        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.userMessage("改用别的方案"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(1, attempts.get());
        assertTrue(completed.getPendingToolCalls().isEmpty());
    }

    @Test
    public void shouldAnswerManualUserInputWaitWithMultimodalMessage() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertEquals(2, messages.size());
            UserMessage latest = (UserMessage) messages.get(1);
            assertEquals("看这张图继续", latest.getTextContent());
            assertEquals("https://example.com/new.png", latest.getImageUrls().get(0));
            return new AiMessage("continued with image");
        });
        Agent agent = Agent.builder("user-wait-interruption").chatModel(model).build();
        AgentRunner runner = new AgentRunner();
        AgentTurn waiting = runner.suspend(runner.start(agent, "original"),
            AgentSuspension.userInput("need more information"));
        UserMessage message = new UserMessage("看这张图继续");
        message.addImageUrl("https://example.com/new.png");
        AgentResumeCommand command = AgentResumeCommand.userMessage(message)
            .withMetadata("source", "chat-ui");
        message.addImageUrl("https://example.com/late-mutation.png");

        AgentTurn completed = runner.resume(waiting, command);

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals("continued with image", completed.getFinalOutput());
        assertTrue(completed.getToolInterruptions().isEmpty());
        assertEquals("INPUT_RESPONSE",
            completed.getMetadata().get("lastUserMessageDisposition"));
        assertEquals("chat-ui", ((Map<?, ?>) completed.getMetadata()
            .get("lastResumeCommandMetadata")).get("source"));
    }

    @Test
    public void shouldRoundTripModelFailureInSnapshot() {
        AgentModelFailure failure = AgentModelFailure.from(new ModelQuotaExceededException(
            "quota", 429, "insufficient_quota", "insufficient_quota"), 3);
        AgentTurnState state = AgentTurnState.builder("model-failure-turn",
                AgentExecutionPolicy.defaults(), System.currentTimeMillis())
            .status(AgentTurnStatus.WAITING_FOR_MODEL)
            .executionPoint(AgentTurnExecutionPoint.INVOKE_MODEL)
            .suspension(AgentSuspension.model(failure))
            .build();
        AgentTurnSnapshot source = AgentTurnSnapshot.of("agent", "1", state);
        FastjsonAgentStoreSerializer serializer = new FastjsonAgentStoreSerializer();

        AgentTurnSnapshot restored = serializer.deserialize(
            serializer.serialize(source), AgentTurnSnapshot.class);
        AgentModelFailure restoredFailure = restored.getState().getSuspension().getModelFailure();

        assertNotNull(restoredFailure);
        assertEquals(failure.getFailureId(), restoredFailure.getFailureId());
        assertEquals(AgentModelFailureType.QUOTA_EXCEEDED, restoredFailure.getType());
        assertEquals(3, restoredFailure.getModelAttempt());
    }

    private static void assertModelFailure(RuntimeException error,
                                           AgentModelFailureType expectedType,
                                           int expectedStatus, String expectedCode) {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            throw error;
        });
        AgentTurn turn = new AgentRunner().run(
            Agent.builder("model-failure-" + expectedType).chatModel(model).build(),
            "hello");

        assertEquals(AgentTurnStatus.WAITING_FOR_MODEL, turn.getStatus());
        assertEquals(AgentSuspensionType.MODEL, turn.getSuspension().getType());
        assertEquals(AgentTurnExecutionPoint.INVOKE_MODEL, turn.getExecutionPoint());
        assertNotNull(turn.getModelFailure());
        assertEquals(expectedType, turn.getModelFailure().getType());
        assertEquals(expectedStatus, turn.getModelFailure().getHttpStatus());
        assertEquals(expectedCode, turn.getModelFailure().getErrorCode());
        assertEquals(turn.getModelFailure().getFailureId(),
            turn.getSuspension().getCorrelationId());
    }

    private static ModelRateLimitException rateLimited() {
        return new ModelRateLimitException("slow down", 429,
            "rate_limit", "rate_limit_error", 2500L);
    }

    private static AgentActionMessage approvalAction(DefaultChatMemory memory) {
        for (Message message : memory.getMessages(Integer.MAX_VALUE)) {
            if (message instanceof AgentActionMessage) {
                return (AgentActionMessage) message;
            }
        }
        throw new AssertionError("approval action not found");
    }
}
