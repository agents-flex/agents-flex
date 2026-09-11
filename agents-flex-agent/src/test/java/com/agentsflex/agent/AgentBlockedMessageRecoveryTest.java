/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.exception.AgentFormRequiredException;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.FastjsonAgentStoreSerializer;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.tool.AgentFormDefinition;
import com.agentsflex.agent.tool.AgentUserInputTool;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.memory.DefaultChatMemory;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolExecutionTarget;
import com.agentsflex.core.model.exception.ModelQuotaExceededException;
import com.agentsflex.core.model.exception.ModelRateLimitException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * 阻塞 Turn 接收普通消息或强制重规划消息时的细粒度恢复契约。
 *
 * <p>这些测试刻意检查中间 Snapshot 和消息顺序。只断言最终回答无法发现未闭合 ToolCall、重复消费
 * 用户消息、连续重试预算未重置等问题。</p>
 */
public class AgentBlockedMessageRecoveryTest {

    @Test
    public void shouldInterruptScheduledModelRetryAndRunImmediately() {
        AgentScenarioTestSupport.QueueChatModel model = queue();
        model.enqueue(prompt -> {
            throw rateLimited();
        });
        model.enqueue(prompt -> {
            assertEquals("补充后继续", last(prompt.getMessages()).getTextContent());
            return new AiMessage("已恢复");
        });
        Agent agent = Agent.builder("scheduled-model-message")
            .chatModel(model)
            .executionPolicy(AgentExecutionPolicy.builder()
                .retryPolicy(AgentRetryPolicy.builder().maxRetries(1)
                    .initialDelayMillis(60000).maxDelayMillis(60000).build())
                .build())
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = conversationRunner(agent, store, "scheduled-model");

        AgentTurn scheduled = runner.run(agent, "scheduled-model", "开始");
        assertEquals(AgentTurnStatus.RETRY_SCHEDULED, scheduled.getStatus());
        AgentTurn completed = runner.run(agent, "scheduled-model", "补充后继续");

        assertEquals(scheduled.getId(), completed.getId());
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(0L, completed.getNextRunnableAt());
        assertEquals(1, completed.getRetryCount());
        assertEquals(0, completed.getConsecutiveRetryCount());
        assertEquals(1, completed.getModelFailureHistory().size());
    }

    @Test
    public void shouldUseOrdinaryMessageAsManualUserInputResponse() {
        AgentScenarioTestSupport.QueueChatModel model = queue();
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertEquals(2, messages.size());
            assertTrue(messages.get(1) instanceof UserMessage);
            assertEquals("上海", messages.get(1).getTextContent());
            return new AiMessage("目的地已确认");
        });
        Agent agent = Agent.builder("manual-user-input").chatModel(model).build();
        AgentRunner runner = new AgentRunner();
        AgentTurn waiting = runner.suspend(runner.start(agent, "请规划行程"),
            AgentSuspension.userInput("请提供目的地"));

        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.userMessage("上海"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertTrue(completed.getToolInterruptions().isEmpty());
        assertEquals("INPUT_RESPONSE",
            completed.getMetadata().get("lastUserMessageDisposition"));
    }

    @Test
    public void shouldUseOrdinaryTextAsRequestUserInputToolResult() {
        AgentScenarioTestSupport.QueueChatModel model = queue();
        model.enqueue(prompt -> toolCalls(new ToolCall("input-1",
            AgentUserInputTool.NAME, "{\"formKey\":\"destination\"}")));
        model.enqueue(prompt -> {
            Message message = last(prompt.getMessages());
            assertTrue(message instanceof ToolMessage);
            ToolMessage result = (ToolMessage) message;
            assertEquals("input-1", result.getToolCallId());
            assertTrue(result.getContent().contains("上海"));
            return new AiMessage("收到目的地");
        });
        Agent agent = Agent.builder("request-user-input-message")
            .chatModel(model)
            .tool(userInputTool("destination"))
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = conversationRunner(agent, store, "request-user-input");

        AgentTurn waiting = runner.run(agent, "request-user-input", "规划行程");
        AgentTurn completed = runner.run(agent, "request-user-input", "上海");

        assertEquals(AgentTurnStatus.WAITING_FOR_USER, waiting.getStatus());
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertTrue(completed.getToolInterruptions().isEmpty());
        assertEquals("INPUT_RESPONSE",
            completed.getMetadata().get("lastUserMessageDisposition"));
    }

    @Test
    public void shouldReplanInsteadOfGuessingStructuredBusinessForm() {
        AgentScenarioTestSupport.QueueChatModel model = queue();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> toolCalls(new ToolCall("ticket-1", "ticket", "{}")));
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertTrue(messages.get(messages.size() - 2) instanceof ToolMessage);
            assertEquals("请改为只查询状态", last(messages).getTextContent());
            return new AiMessage("已重新规划");
        });
        AgentFormDefinition form = form("ticket-details");
        Agent agent = Agent.builder("business-form-message")
            .chatModel(model)
            .tool(tool("ticket", args -> {
                executions.incrementAndGet();
                throw new AgentFormRequiredException(form);
            }))
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = conversationRunner(agent, store, "business-form");

        AgentTurn waiting = runner.run(agent, "business-form", "创建工单");
        AgentTurn completed = runner.run(agent, "business-form", "请改为只查询状态");

        assertEquals(AgentTurnStatus.WAITING_FOR_USER, waiting.getStatus());
        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(1, executions.get());
        assertEquals(1, completed.getToolInterruptions().size());
        assertEquals(AgentSuspensionType.USER_INPUT,
            completed.getToolInterruptions().get(0).getSuspensionType());
        assertEquals("REPLAN", completed.getMetadata().get("lastUserMessageDisposition"));
    }

    @Test
    public void shouldForceReplanEvenForRequestUserInput() {
        AgentScenarioTestSupport.QueueChatModel model = queue();
        model.enqueue(prompt -> toolCalls(new ToolCall("input-2",
            AgentUserInputTool.NAME, "{\"formKey\":\"destination\"}")));
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertTrue(messages.get(messages.size() - 2) instanceof ToolMessage);
            assertTrue(last(messages) instanceof UserMessage);
            return new AiMessage("已放弃原问题");
        });
        Agent agent = Agent.builder("forced-replan")
            .chatModel(model).tool(userInputTool("destination")).build();
        AgentRunner runner = new AgentRunner();
        AgentTurn waiting = runner.run(agent, "规划行程");

        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.replanWithMessage("不用规划了"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(1, completed.getToolInterruptions().size());
        assertEquals("input-2", completed.getToolInterruptions().get(0).getToolCallId());
        assertEquals(AgentResumeCommandType.REPLAN_WITH_MESSAGE.name(),
            completed.getMetadata().get("lastResumeCommand"));
    }

    @Test
    public void shouldCloseAllPendingToolCallsInDeclarationOrder() {
        AgentScenarioTestSupport.QueueChatModel model = queue();
        ToolCall first = new ToolCall("call-1", "write", "{}");
        ToolCall second = new ToolCall("call-2", "write", "{}");
        ToolCall third = new ToolCall("call-3", "write", "{}");
        model.enqueue(prompt -> toolCalls(first, second, third));
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            int start = messages.size() - 4;
            for (int index = 0; index < 3; index++) {
                assertTrue(messages.get(start + index) instanceof ToolMessage);
                assertEquals("call-" + (index + 1),
                    ((ToolMessage) messages.get(start + index)).getToolCallId());
            }
            assertTrue(messages.get(messages.size() - 1) instanceof UserMessage);
            return new AiMessage("已取消全部写入");
        });
        Agent agent = Agent.builder("multiple-tool-interruption")
            .chatModel(model)
            .tool(tool("write", args -> "done"))
            .toolApprovalPolicy((turn, call, value) ->
                ToolApprovalDecision.requireApproval().message("需审批").build())
            .build();
        AgentRunner runner = new AgentRunner();

        AgentTurn waiting = runner.run(agent, "执行三个写入");
        assertEquals(AgentTurnStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.replanWithMessage("全部取消"));

        assertEquals(3, completed.getToolInterruptions().size());
        assertEquals("call-1", completed.getToolInterruptions().get(0).getToolCallId());
        assertEquals("call-3", completed.getToolInterruptions().get(2).getToolCallId());
        assertTrue(completed.getPendingToolCalls().isEmpty());
    }

    @Test
    public void shouldPersistExternalCancellationAndRejectLateResultAfterReblocking() {
        AgentScenarioTestSupport.QueueChatModel model = queue();
        model.enqueue(prompt -> toolCalls(new ToolCall("external-old", "browser", "{}")));
        model.enqueue(prompt -> toolCalls(new ToolCall("external-new", "browser", "{}")));
        Tool external = Tool.builder("browser", "browser")
            .executionTarget(ToolExecutionTarget.EXTERNAL).build();
        Agent agent = Agent.builder("external-cancel-event")
            .chatModel(model).tool(external).build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        List<AgentEvent> events = new ArrayList<>();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent))
            .addEventListener(events::add);

        AgentTurn firstWait = runner.run(agent, "打开旧页面");
        AgentTurn secondWait = runner.resume(firstWait,
            AgentResumeCommand.replanWithMessage("改为打开新页面"));

        assertEquals(AgentTurnStatus.WAITING_FOR_TOOL, secondWait.getStatus());
        assertEquals("external-new", secondWait.getSuspension().getCorrelationId());
        AgentToolInterruption interruption = runner.restore(secondWait.getId())
            .getToolInterruptions().get(0);
        assertEquals("external-old", interruption.getToolCallId());
        assertNotNull(interruption.getSourceMessageId());
        assertEquals(1, count(events, AgentEventType.TOOL_INTERRUPTED));
        assertEquals(1, count(events, AgentEventType.EXTERNAL_TOOL_CANCEL_REQUESTED));
        FastjsonAgentStoreSerializer serializer = new FastjsonAgentStoreSerializer();
        AgentTurnSnapshot decoded = serializer.deserialize(
            serializer.serialize(secondWait.toSnapshot()), AgentTurnSnapshot.class);
        assertEquals("external-old",
            decoded.getState().getToolInterruptions().get(0).getToolCallId());
        assertEquals(interruption.getSourceMessageId(),
            decoded.getState().getProcessedUserMessageIds().get(0));
        try {
            runner.resume(secondWait,
                AgentResumeCommand.toolResult("external-old", "late"));
            fail("old external result must not match the new suspension");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("correlationId"));
        }
        assertEquals("external-new", secondWait.getSuspension().getCorrelationId());
    }

    @Test
    public void shouldRewriteReusedToolCallIdToPreventLateResultAba() {
        AgentScenarioTestSupport.QueueChatModel model = queue();
        model.enqueue(prompt -> toolCalls(new ToolCall("reused-id", "browser", "{}")));
        // 模拟供应商在下一模型回合错误地复用相同 ToolCall ID。
        model.enqueue(prompt -> toolCalls(new ToolCall("reused-id", "browser", "{}")));
        Tool external = Tool.builder("browser", "browser")
            .executionTarget(ToolExecutionTarget.EXTERNAL).build();
        Agent agent = Agent.builder("external-aba")
            .chatModel(model).tool(external).build();
        AgentRunner runner = new AgentRunner();

        AgentTurn firstWait = runner.run(agent, "打开页面");
        AgentTurn secondWait = runner.resume(firstWait,
            AgentResumeCommand.replanWithMessage("重新打开"));

        String newCorrelationId = secondWait.getSuspension().getCorrelationId();
        assertFalse("reused-id".equals(newCorrelationId));
        try {
            runner.resume(secondWait,
                AgentResumeCommand.toolResult("reused-id", "late"));
            fail("late result must not match a regenerated correlation id");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("correlationId"));
        }
        assertEquals(newCorrelationId, secondWait.getSuspension().getCorrelationId());
    }

    @Test
    public void shouldPreserveFailureHistoryAndAllowRecoveryAtIterationLimit() {
        AgentScenarioTestSupport.QueueChatModel model = queue();
        model.enqueue(prompt -> {
            throw quota();
        });
        model.enqueue(prompt -> new AiMessage("恢复成功"));
        Agent agent = Agent.builder("iteration-failure-accounting")
            .chatModel(model)
            .executionPolicy(AgentExecutionPolicy.builder().maxIterations(1).build())
            .build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));

        AgentTurn waiting = runner.run(agent, "开始");
        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.retryModel(waiting.getModelFailure().getFailureId()));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(2, completed.getIterationCount());
        assertEquals(1, completed.getModelInvocationFailureCount());
        assertEquals(1, completed.getSuccessfulModelInvocationCount());
        assertEquals(1, completed.getModelFailureHistory().size());
        assertEquals(null, completed.getModelFailure());

        FastjsonAgentStoreSerializer serializer = new FastjsonAgentStoreSerializer();
        AgentTurnSnapshot snapshot = completed.toSnapshot();
        AgentTurnSnapshot decoded = serializer.deserialize(
            serializer.serialize(snapshot), AgentTurnSnapshot.class);
        assertEquals(1, decoded.getState().getModelFailureHistory().size());
        assertEquals(waiting.getModelFailureHistory().get(0).getFailureId(),
            decoded.getState().getModelFailureHistory().get(0).getFailureId());
    }

    @Test
    public void shouldResetConsecutiveRetryBudgetButKeepCumulativeCount() {
        AgentScenarioTestSupport.QueueChatModel model = queue();
        model.enqueue(prompt -> {
            throw rateLimited();
        });
        model.enqueue(prompt -> {
            throw rateLimited();
        });
        Agent agent = Agent.builder("retry-budget-reset")
            .chatModel(model)
            .executionPolicy(AgentExecutionPolicy.builder()
                .retryPolicy(AgentRetryPolicy.builder().maxRetries(1)
                    .initialDelayMillis(60000).maxDelayMillis(60000).build())
                .build())
            .build();
        AgentRunner runner = new AgentRunner();

        AgentTurn first = runner.run(agent, "开始");
        assertEquals(AgentTurnStatus.RETRY_SCHEDULED, first.getStatus());
        AgentTurn second = runner.resume(first,
            AgentResumeCommand.userMessage("条件已改变"));

        assertEquals(AgentTurnStatus.RETRY_SCHEDULED, second.getStatus());
        assertEquals(2, second.getRetryCount());
        assertEquals(1, second.getConsecutiveRetryCount());
        assertEquals(2, second.getModelFailureHistory().size());
    }

    @Test
    public void shouldDeduplicateSameMessageAcrossRunners() {
        AgentScenarioTestSupport.QueueChatModel model = queue();
        model.enqueue(prompt -> {
            throw quota();
        });
        model.enqueue(prompt -> new AiMessage("只执行一次"));
        Agent agent = Agent.builder("message-idempotency").chatModel(model).build();
        InMemoryAgentTurnStore store = new InMemoryAgentTurnStore();
        InMemoryAgentLoader loader = new InMemoryAgentLoader(agent);
        DefaultChatMemory memory = new DefaultChatMemory("idempotent-conversation");
        AgentRunner firstRunner = AgentRunner.builder().turnStore(store)
            .agentLoader(loader).chatMemoryProvider(id -> memory).build();
        AgentRunner secondRunner = AgentRunner.builder().turnStore(store)
            .agentLoader(loader).chatMemoryProvider(id -> memory).build();
        AgentTurn waiting = firstRunner.run(agent, "idempotent-conversation", "开始");
        UserMessage message = new UserMessage("继续");
        message.setMessageId("message-idempotency-1");

        AgentTurn firstSubmission = firstRunner.submitMessage(
            agent, "idempotent-conversation", message);
        AgentTurn duplicate = secondRunner.submitMessage(
            agent, "idempotent-conversation", message);

        assertEquals(waiting.getId(), duplicate.getId());
        assertEquals(firstSubmission.getVersion(), duplicate.getVersion());
        assertEquals(1, countMessages(duplicate.getConversationHistory(),
            "message-idempotency-1"));
        assertEquals(1, model.getCallCount());

        List<AgentTurn> processed = new AgentWorker(
            "idempotent-worker", secondRunner, 1000).pollAndRun(1);
        assertEquals(1, processed.size());
        assertEquals(AgentTurnStatus.COMPLETED, processed.get(0).getStatus());
        assertEquals(2, model.getCallCount());
    }

    @Test
    public void shouldAcceptOrdinaryMessageAfterUserInputSuspensionExpires()
        throws Exception {
        AgentScenarioTestSupport.QueueChatModel model = queue();
        model.enqueue(prompt -> new AiMessage("仍然接受回答"));
        Agent agent = Agent.builder("expired-user-message").chatModel(model).build();
        AgentRunner runner = new AgentRunner();
        AgentTurn waiting = runner.suspend(runner.start(agent, "开始"),
            AgentSuspension.userInput("请回答", 1L));
        Thread.sleep(10L);

        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.userMessage("迟到但有效的回答"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals("INPUT_RESPONSE",
            completed.getMetadata().get("lastUserMessageDisposition"));
    }

    @Test
    public void shouldAcceptForcedReplanAfterApprovalSuspensionExpires()
        throws Exception {
        AgentScenarioTestSupport.QueueChatModel model = queue();
        model.enqueue(prompt -> toolCalls(new ToolCall("approval-expired", "write", "{}")));
        model.enqueue(prompt -> new AiMessage("已放弃过期审批"));
        Agent agent = Agent.builder("expired-approval-replan")
            .chatModel(model)
            .tool(tool("write", args -> "done"))
            .toolApprovalPolicy((turn, call, value) ->
                ToolApprovalDecision.requireApproval().message("需审批").build())
            .executionPolicy(AgentExecutionPolicy.builder()
                .approvalTimeoutMillis(1L).build())
            .build();
        AgentRunner runner = new AgentRunner();
        AgentTurn waiting = runner.run(agent, "执行写入");
        Thread.sleep(10L);

        AgentTurn completed = runner.resume(waiting,
            AgentResumeCommand.replanWithMessage("取消写入"));

        assertEquals(AgentTurnStatus.COMPLETED, completed.getStatus());
        assertEquals(1, completed.getToolInterruptions().size());
    }

    private static AgentScenarioTestSupport.QueueChatModel queue() {
        return new AgentScenarioTestSupport.QueueChatModel();
    }

    private static ModelQuotaExceededException quota() {
        return new ModelQuotaExceededException(
            "quota", 429, "insufficient_quota", "insufficient_quota");
    }

    private static ModelRateLimitException rateLimited() {
        return new ModelRateLimitException(
            "rate limited", 429, "rate_limit", "rate_limit_error", 60000L);
    }

    private static AgentRunner conversationRunner(Agent agent,
                                                  InMemoryAgentTurnStore store,
                                                  String conversationId) {
        DefaultChatMemory memory = new DefaultChatMemory(conversationId);
        return AgentRunner.builder().turnStore(store)
            .agentLoader(new InMemoryAgentLoader(agent))
            .chatMemoryProvider(id -> memory).build();
    }

    private static Tool userInputTool(String formKey) {
        return AgentUserInputTool.builder().form(form(formKey)).build();
    }

    private static AgentFormDefinition form(String formKey) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.singletonMap("value",
            Collections.singletonMap("type", "string")));
        return AgentFormDefinition.builder(formKey)
            .description("补充必要信息").schema(schema).build();
    }

    private static Message last(List<Message> messages) {
        return messages.get(messages.size() - 1);
    }

    private static int count(List<AgentEvent> events, AgentEventType type) {
        int result = 0;
        for (AgentEvent event : events) if (event.getType() == type) result++;
        return result;
    }

    private static int countMessages(List<Message> messages, String messageId) {
        int result = 0;
        for (Message message : messages) {
            if (messageId.equals(message.getMessageId())) result++;
        }
        return result;
    }
}
