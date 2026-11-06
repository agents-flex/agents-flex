package com.agentsflex.agent;

import com.agentsflex.agent.compression.AgentContextCompressionPolicy;
import com.agentsflex.agent.compression.AgentContextCompressionState;
import com.agentsflex.agent.compression.AgentContextCompressionStateStore;
import com.agentsflex.agent.context.AgentContextCompressionReason;
import com.agentsflex.agent.context.AgentContextUsagePolicy;
import com.agentsflex.agent.context.AgentContextUsageState;
import com.agentsflex.agent.context.AgentContextUsageStatus;
import com.agentsflex.agent.context.InMemoryAgentContextUsageStateStore;
import com.agentsflex.core.memory.DefaultChatMemory;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatModelCapabilities;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.exception.TokenLimitExceededException;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventType;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 服务端 Usage、阈值压缩、工具循环和 Token 上限恢复的端到端验收测试。
 */
public class AgentContextUsageIntegrationTest {

    /**
     * 验证跨会话轮次的阈值标记会在下一次模型调用前压缩并重新确认基准。
     */
    @Test
    public void shouldCompressBeforeNextConversationCallAndInvalidateOldBaseline() {
        DefaultChatMemory memory = history("threshold-conversation", 3);
        InMemoryCompressionStore compressionStore = new InMemoryCompressionStore();
        InMemoryAgentContextUsageStateStore usageStore = new InMemoryAgentContextUsageStateStore();
        AtomicInteger compressions = new AtomicInteger();
        UsageModel model = new UsageModel(false, 20, 5);
        Agent agent = agent("usage-agent", model, compressionStore, usageStore, compressions);
        String scope = "usage-agent:1:test:model";
        AgentContextUsageState high = AgentContextUsageState
            .empty("threshold-conversation", scope)
            .confirmed(80, 10, 90, 90, true,
                AgentContextCompressionReason.THRESHOLD_REACHED, 1L);
        assertTrue(usageStore.save(high, 0));

        AgentRunner runner = AgentRunner.builder()
            .chatMemoryProvider(id -> memory)
            .build();
        AgentTurn turn = runner.run(agent, "threshold-conversation", "new question");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals(1, compressions.get());
        AgentContextUsageState state = usageStore.load("threshold-conversation", scope);
        assertNotNull(state);
        assertEquals(AgentContextUsageStatus.CONFIRMED_BASELINE, state.getStatus());
        assertFalse(state.isCompressionRequired());
        assertEquals(25, state.getNextCallBaselineTokens());
        assertEquals(1, model.getCallCount());
    }

    /**
     * 验证输入上下文超限后压缩历史，并固定同一模型重试。
     */
    @Test
    public void shouldCompressAndRetrySameModelAfterInputContextLimit() {
        DefaultChatMemory memory = history("recovery-conversation", 3);
        InMemoryCompressionStore compressionStore = new InMemoryCompressionStore();
        InMemoryAgentContextUsageStateStore usageStore = new InMemoryAgentContextUsageStateStore();
        AtomicInteger compressions = new AtomicInteger();
        UsageModel model = new UsageModel(true, 30, 4);
        Agent agent = agent("recovery-agent", model, compressionStore, usageStore, compressions);

        AgentTurn turn = AgentRunner.builder()
            .chatMemoryProvider(id -> memory)
            .build()
            .run(agent, "recovery-conversation", "new question");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals(2, model.getCallCount());
        assertEquals(1, compressions.get());
        assertEquals(1, turn.getModelInvocationFailureCount());
        AgentContextUsageState state = usageStore.load("recovery-conversation",
            "recovery-agent:1:test:model");
        assertNotNull(state);
        assertEquals(AgentContextUsageStatus.CONFIRMED_BASELINE, state.getStatus());
        assertEquals(34, state.getNextCallBaselineTokens());
    }

    /**
     * 验证同一 Turn 的工具循环会在第二次真实模型调用前消费阈值标记。
     */
    @Test
    public void thresholdMustCompressBeforeLaterModelCallInSameToolTurn() {
        DefaultChatMemory memory = history("same-turn-threshold", 3);
        InMemoryCompressionStore compressionStore = new InMemoryCompressionStore();
        InMemoryAgentContextUsageStateStore usageStore = new InMemoryAgentContextUsageStateStore();
        AtomicInteger compressions = new AtomicInteger();
        AtomicReference<List<Message>> secondPrompt = new AtomicReference<>();
        UsageModel model = new UsageModel(false, 0, 0) {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                incrementCallCount();
                AiMessage message;
                if (getCallCount() == 1) {
                    message = new AiMessage();
                    message.setToolCalls(Collections.singletonList(
                        new ToolCall("lookup-1", "lookup", "{}")));
                    setUsage(message, 80, 10);
                } else {
                    secondPrompt.set(new ArrayList<>(prompt.getMessages()));
                    message = new AiMessage("done");
                    setUsage(message, 20, 5);
                }
                ChatContext context = new ChatContext();
                context.setPrompt(prompt);
                return new AiMessageResponse(context, null, message);
            }
        };
        Agent agent = Agent.builder("same-turn-agent")
            .chatModel(model)
            .tool(tool("lookup"))
            .compressionPolicy(AgentContextCompressionPolicy.builder()
                .stateStore(compressionStore)
                .compressor(messages -> {
                    compressions.incrementAndGet();
                    return Collections.<Message>singletonList(
                        new UserMessage("compressed summary"));
                })
                .keepRecentTurns(1)
                .build())
            .contextUsagePolicy(AgentContextUsagePolicy.builder()
                .enabled(true)
                .stateStore(usageStore)
                .compressionThresholdRatio(0.90d)
                .reservedOutputTokens(10)
                .build())
            .build();
        List<AgentEvent> events = new ArrayList<>();

        AgentTurn turn = AgentRunner.builder().chatMemoryProvider(id -> memory).build()
            .addEventListener(events::add)
            .run(agent, "same-turn-threshold", "question");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals(2, model.getCallCount());
        assertEquals(1, compressions.get());
        assertNotNull(secondPrompt.get());
        assertTrue(containsText(secondPrompt.get(), "compressed summary"));
        assertTrue(containsType(secondPrompt.get(), ToolMessage.class));
        assertTrue(indexOf(events, AgentEventType.CONTEXT_COMPRESSION_REQUIRED)
            < indexOf(events, AgentEventType.CONTEXT_COMPRESSION_STARTED));
        AgentEvent compressed = find(events, AgentEventType.CONTEXT_COMPRESSION_COMPLETED);
        assertNotNull(compressed);
        assertEquals(AgentContextUsageStatus.BASELINE_INVALIDATED,
            compressed.getData().get("usageStatus"));
        assertEquals(Boolean.FALSE, compressed.getData().get("compressionRequired"));
        assertEquals(Boolean.FALSE, compressed.getData().get("usageBaselineValid"));
        AgentContextUsageState state = usageStore.load("same-turn-threshold",
            "same-turn-agent:1:test:model");
        assertNotNull(state);
        assertEquals(AgentContextUsageStatus.CONFIRMED_BASELINE, state.getStatus());
        assertFalse(state.isCompressionRequired());
        assertEquals(25, state.getNextCallBaselineTokens());
    }

    /**
     * 验证进程内 Usage Store 严格执行版本加一的 CAS 规则。
     */
    @Test
    public void inMemoryUsageStoreMustUseCasVersions() {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        AgentContextUsageState first = AgentContextUsageState.empty("c", "s")
            .confirmed(1, 2, 3, 3, false, null, 1);
        assertTrue(store.save(first, 0));
        assertFalse(store.save(first, 0));
        AgentContextUsageState invalidated = first.invalidated(
            AgentContextCompressionReason.MANUAL, 2);
        assertTrue(store.save(invalidated, first.getVersion()));
        assertEquals(AgentContextUsageStatus.BASELINE_INVALIDATED,
            store.load("c", "s").getStatus());
    }

    /**
     * 验证本地 Token 字段不能建立供应商确认基准。
     */
    @Test
    public void localTokenFieldsMustNotCreateProviderConfirmedUsageState() {
        DefaultChatMemory memory = new DefaultChatMemory("local-only");
        InMemoryCompressionStore compressionStore = new InMemoryCompressionStore();
        InMemoryAgentContextUsageStateStore usageStore = new InMemoryAgentContextUsageStateStore();
        AtomicInteger compressions = new AtomicInteger();
        UsageModel model = new UsageModel(false, 0, 0) {
            @Override
            AiMessage responseMessage() {
                AiMessage message = new AiMessage("done");
                message.setLocalPromptTokens(90);
                message.setLocalCompletionTokens(5);
                message.setLocalTotalTokens(95);
                return message;
            }
        };
        Agent agent = agent("local-only-agent", model, compressionStore, usageStore, compressions);
        List<AgentEvent> events = new ArrayList<>();

        AgentTurn turn = AgentRunner.builder().chatMemoryProvider(id -> memory).build()
            .addEventListener(events::add)
            .run(agent, "local-only", "question");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals(0, compressions.get());
        assertNull(usageStore.load("local-only", "local-only-agent:1:test:model"));
        AgentEvent completed = find(events, AgentEventType.MODEL_COMPLETED);
        assertNotNull(completed);
        assertEquals("UNAVAILABLE", completed.getData().get("usageSource"));
    }

    /**
     * 验证后续成功响应缺失 Usage 时旧确认基准立即失效。
     */
    @Test
    public void missingProviderUsageMustInvalidatePreviouslyConfirmedBaseline() {
        DefaultChatMemory memory = new DefaultChatMemory("usage-disappeared");
        InMemoryCompressionStore compressionStore = new InMemoryCompressionStore();
        InMemoryAgentContextUsageStateStore usageStore = new InMemoryAgentContextUsageStateStore();
        AtomicInteger compressions = new AtomicInteger();
        String scope = "usage-disappeared-agent:1:test:model";
        AgentContextUsageState confirmed = AgentContextUsageState
            .empty("usage-disappeared", scope)
            .confirmed(30, 5, 35, 35, false, null, 1L);
        assertTrue(usageStore.save(confirmed, 0));
        UsageModel model = new UsageModel(false, 0, 0) {
            @Override
            AiMessage responseMessage() {
                AiMessage message = new AiMessage("done");
                message.setLocalPromptTokens(40);
                message.setLocalCompletionTokens(6);
                message.setLocalTotalTokens(46);
                return message;
            }
        };
        Agent agent = agent("usage-disappeared-agent", model, compressionStore,
            usageStore, compressions);
        List<AgentEvent> events = new ArrayList<>();

        AgentTurn turn = AgentRunner.builder().chatMemoryProvider(id -> memory).build()
            .addEventListener(events::add)
            .run(agent, "usage-disappeared", "question");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        AgentContextUsageState state = usageStore.load("usage-disappeared", scope);
        assertNotNull(state);
        assertEquals(AgentContextUsageStatus.BASELINE_INVALIDATED, state.getStatus());
        assertFalse(state.isCompressionRequired());
        assertEquals(35, state.getNextCallBaselineTokens());
        AgentEvent completed = find(events, AgentEventType.MODEL_COMPLETED);
        assertEquals("UNAVAILABLE", completed.getData().get("usageSource"));
        assertEquals(AgentContextUsageStatus.BASELINE_INVALIDATED,
            completed.getData().get("usageStatus"));
        assertFalse(completed.getData().containsKey("baselineRatio"));
        assertFalse(completed.getData().containsKey("remainingBaselineTokens"));
    }

    /**
     * 验证只有 input 或只有 output 的不完整 Usage 不建立基准。
     */
    @Test
    public void partialProviderUsageMustNotCreateConfirmedBaseline() {
        InMemoryAgentContextUsageStateStore usageStore = new InMemoryAgentContextUsageStateStore();
        UsageModel model = new UsageModel(false, 0, 0) {
            @Override
            AiMessage responseMessage() {
                AiMessage message = new AiMessage("done");
                message.setPromptTokens(60);
                return message;
            }
        };
        Agent agent = agent("partial-usage-agent", model, new InMemoryCompressionStore(),
            usageStore, new AtomicInteger());
        List<AgentEvent> events = new ArrayList<>();

        AgentTurn turn = AgentRunner.builder()
            .chatMemoryProvider(id -> new DefaultChatMemory(id))
            .build()
            .addEventListener(events::add)
            .run(agent, "partial-usage", "question");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertNull(usageStore.load("partial-usage", "partial-usage-agent:1:test:model"));
        assertEquals("UNAVAILABLE",
            find(events, AgentEventType.MODEL_COMPLETED).getData().get("usageSource"));
    }

    /**
     * 验证压缩状态和真实调用使用同一次动态模型选择结果。
     */
    @Test
    public void thresholdCompressionMustUseTheSingleActuallySelectedModel() {
        DefaultChatMemory memory = history("selected-model", 3);
        InMemoryAgentContextUsageStateStore usageStore = new InMemoryAgentContextUsageStateStore();
        InMemoryCompressionStore compressionStore = new InMemoryCompressionStore();
        AtomicInteger selections = new AtomicInteger();
        AtomicInteger compressions = new AtomicInteger();
        UsageModel model = new UsageModel(false, 20, 5);
        String scope = "selector-agent:1:test:model";
        AgentContextUsageState high = AgentContextUsageState.empty("selected-model", scope)
            .confirmed(80, 10, 90, 90, true,
                AgentContextCompressionReason.THRESHOLD_REACHED, 1L);
        assertTrue(usageStore.save(high, 0));
        Agent agent = Agent.builder("selector-agent")
            .chatModel(model)
            .modelSelector((turn, prompt, text, multimodal) -> {
                selections.incrementAndGet();
                return text;
            })
            .compressionPolicy(AgentContextCompressionPolicy.builder()
                .stateStore(compressionStore)
                .compressor(messages -> {
                    compressions.incrementAndGet();
                    return Collections.<Message>singletonList(
                        new UserMessage("compressed summary"));
                })
                .keepRecentTurns(1)
                .build())
            .contextUsagePolicy(AgentContextUsagePolicy.builder()
                .enabled(true)
                .stateStore(usageStore)
                .compressionThresholdRatio(0.90d)
                .reservedOutputTokens(10)
                .build())
            .build();

        AgentTurn turn = AgentRunner.builder().chatMemoryProvider(id -> memory).build()
            .run(agent, "selected-model", "question");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals(1, selections.get());
        assertEquals(1, model.getCallCount());
        assertEquals(1, compressions.get());
    }

    /**
     * 验证 OUTPUT 阶段超限不会错误压缩输入历史。
     */
    @Test
    public void outputTokenLimitMustNotTriggerContextCompression() {
        DefaultChatMemory memory = history("output-limit", 3);
        InMemoryCompressionStore compressionStore = new InMemoryCompressionStore();
        InMemoryAgentContextUsageStateStore usageStore = new InMemoryAgentContextUsageStateStore();
        AtomicInteger compressions = new AtomicInteger();
        UsageModel model = new UsageModel(false, 0, 0) {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                incrementCallCount();
                throw new TokenLimitExceededException("output too long", 400,
                    "max_output_tokens", "invalid_request",
                    TokenLimitExceededException.Phase.OUTPUT);
            }
        };
        Agent agent = agent("output-limit-agent", model, compressionStore, usageStore, compressions);

        AgentTurn turn = AgentRunner.builder().chatMemoryProvider(id -> memory).build()
            .run(agent, "output-limit", "question");

        assertEquals(AgentTurnStatus.WAITING_FOR_MODEL, turn.getStatus());
        assertEquals(0, compressions.get());
        assertEquals(1, model.getCallCount());
    }

    /**
     * 验证策略和请求均未指定预留时回退到模型最大输出能力。
     */
    @Test
    public void modelMaxOutputMustBeReservedWhenPolicyAndRequestDoNotSpecifyIt() {
        InMemoryCompressionStore compressionStore = new InMemoryCompressionStore();
        InMemoryAgentContextUsageStateStore usageStore = new InMemoryAgentContextUsageStateStore();
        UsageModel model = new UsageModel(false, 73, 1);
        Agent agent = Agent.builder("model-output-reserve-agent")
            .chatModel(model)
            .compressionPolicy(AgentContextCompressionPolicy.builder()
                .stateStore(compressionStore)
                .compressor(messages -> Collections.<Message>singletonList(
                    new UserMessage("compressed summary")))
                .keepRecentTurns(1)
                .build())
            .contextUsagePolicy(AgentContextUsagePolicy.builder()
                .enabled(true)
                .stateStore(usageStore)
                .compressionThresholdRatio(0.90d)
                .build())
            .build();

        AgentTurn turn = AgentRunner.builder()
            .chatMemoryProvider(id -> new DefaultChatMemory(id))
            .build()
            .run(agent, "model-output-reserve", "question");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        AgentContextUsageState state = usageStore.load("model-output-reserve",
            "model-output-reserve-agent:1:test:model");
        assertNotNull(state);
        assertTrue(state.isCompressionRequired());
    }

    /**
     * 验证 TOTAL_CONTEXT 阶段超限可以压缩并恢复。
     */
    @Test
    public void totalContextLimitMustTriggerCompressionAndRetry() {
        DefaultChatMemory memory = history("total-context-limit", 3);
        InMemoryAgentContextUsageStateStore usageStore = new InMemoryAgentContextUsageStateStore();
        AtomicInteger compressions = new AtomicInteger();
        UsageModel model = failingOnce(TokenLimitExceededException.Phase.TOTAL_CONTEXT, 20, 5, false);

        AgentTurn turn = AgentRunner.builder().chatMemoryProvider(id -> memory).build()
            .run(agent("total-context-agent", model, new InMemoryCompressionStore(),
                usageStore, compressions), "total-context-limit", "question");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals(2, model.getCallCount());
        assertEquals(1, compressions.get());
        assertEquals(1, turn.getModelInvocationFailureCount());
        assertEquals(Integer.valueOf(1), turn.getMetadata().get("contextCompressionRecoveryAttempt"));
    }

    /**
     * 验证 Token 上限异常被包装后仍可沿 cause 链识别。
     */
    @Test
    public void wrappedInputContextLimitMustStillTriggerRecovery() {
        DefaultChatMemory memory = history("wrapped-context-limit", 3);
        AtomicInteger compressions = new AtomicInteger();
        UsageModel model = failingOnce(TokenLimitExceededException.Phase.INPUT_CONTEXT, 20, 5, true);

        AgentTurn turn = AgentRunner.builder().chatMemoryProvider(id -> memory).build()
            .run(agent("wrapped-context-agent", model, new InMemoryCompressionStore(),
                    new InMemoryAgentContextUsageStateStore(), compressions),
                "wrapped-context-limit", "question");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals(2, model.getCallCount());
        assertEquals(1, compressions.get());
    }

    /**
     * 验证 UNKNOWN 阶段只记录失败，不压缩也不重试。
     */
    @Test
    public void unknownTokenLimitPhaseMustNotTriggerCompression() {
        DefaultChatMemory memory = history("unknown-limit", 3);
        AtomicInteger compressions = new AtomicInteger();
        UsageModel model = alwaysFailing(TokenLimitExceededException.Phase.UNKNOWN);

        AgentTurn turn = AgentRunner.builder().chatMemoryProvider(id -> memory).build()
            .run(agent("unknown-limit-agent", model, new InMemoryCompressionStore(),
                    new InMemoryAgentContextUsageStateStore(), compressions),
                "unknown-limit", "question");

        assertEquals(AgentTurnStatus.WAITING_FOR_MODEL, turn.getStatus());
        assertEquals(1, model.getCallCount());
        assertEquals(0, compressions.get());
        assertEquals(1, turn.getModelInvocationFailureCount());
    }

    /**
     * 验证关闭超限恢复开关后 INPUT_CONTEXT 异常直接进入正常失败流程。
     */
    @Test
    public void disabledTokenLimitRecoveryMustPropagateWithoutCompression() {
        DefaultChatMemory memory = history("disabled-recovery", 3);
        AtomicInteger compressions = new AtomicInteger();
        InMemoryAgentContextUsageStateStore usageStore = new InMemoryAgentContextUsageStateStore();
        UsageModel model = alwaysFailing(TokenLimitExceededException.Phase.INPUT_CONTEXT);
        Agent agent = Agent.builder("disabled-recovery-agent")
            .chatModel(model)
            .compressionPolicy(compressionPolicy(new InMemoryCompressionStore(), compressions))
            .contextUsagePolicy(AgentContextUsagePolicy.builder().enabled(true)
                .stateStore(usageStore).compressOnTokenLimitExceeded(false).build())
            .build();

        AgentTurn turn = AgentRunner.builder().chatMemoryProvider(id -> memory).build()
            .run(agent, "disabled-recovery", "question");

        assertEquals(AgentTurnStatus.WAITING_FOR_MODEL, turn.getStatus());
        assertEquals(1, model.getCallCount());
        assertEquals(0, compressions.get());
    }

    /**
     * 验证 MODEL_COMPLETED 事件包含前端展示所需的完整服务端 Usage 与容量字段。
     */
    @Test
    public void modelCompletedEventMustExposeCompleteProviderUsageAndCapacityData() {
        InMemoryAgentContextUsageStateStore usageStore = new InMemoryAgentContextUsageStateStore();
        List<AgentEvent> events = new ArrayList<>();
        UsageModel model = new UsageModel(false, 60, 10);

        AgentTurn turn = AgentRunner.builder()
            .chatMemoryProvider(DefaultChatMemory::new).build()
            .addEventListener(events::add)
            .run(agent("event-agent", model, new InMemoryCompressionStore(), usageStore,
                new AtomicInteger()), "event-conversation", "question");

        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        AgentEvent event = find(events, AgentEventType.MODEL_COMPLETED);
        assertNotNull(event);
        assertEquals(60, event.getData().get("inputTokens"));
        assertEquals(10, event.getData().get("outputTokens"));
        assertEquals(70, event.getData().get("totalTokens"));
        assertEquals("PROVIDER_CONFIRMED", event.getData().get("usageSource"));
        assertEquals("test:model", event.getData().get("modelKey"));
        assertEquals(100L, event.getData().get("contextWindowTokens"));
        assertEquals(20L, event.getData().get("modelMaxOutputTokens"));
        assertEquals(10L, event.getData().get("reservedOutputTokens"));
        assertEquals(70L, event.getData().get("nextCallBaselineTokens"));
        assertEquals(70d / 90d, (Double) event.getData().get("baselineRatio"), 0.000001d);
        assertEquals(20L, event.getData().get("remainingBaselineTokens"));
        assertEquals(Boolean.FALSE, event.getData().get("compressionRequired"));
        assertEquals(Boolean.TRUE, event.getData().get("usageBaselineValid"));
        assertEquals(AgentContextUsageStatus.CONFIRMED_BASELINE, event.getData().get("usageStatus"));
        assertNull(find(events, AgentEventType.CONTEXT_COMPRESSION_REQUIRED));
    }

    /**
     * 验证请求 maxTokens 的输出预留优先于模型最大输出能力。
     */
    @Test
    public void requestMaxTokensMustOverrideModelOutputReservation() {
        InMemoryAgentContextUsageStateStore usageStore = new InMemoryAgentContextUsageStateStore();
        List<AgentEvent> events = new ArrayList<>();
        UsageModel model = new UsageModel(false, 72, 1);
        ChatOptions request = new ChatOptions();
        request.setMaxTokens(15);
        Agent agent = Agent.builder("request-reserve-agent").chatModel(model)
            .chatOptions(request)
            .compressionPolicy(compressionPolicy(new InMemoryCompressionStore(), new AtomicInteger()))
            .contextUsagePolicy(AgentContextUsagePolicy.builder().enabled(true)
                .stateStore(usageStore).compressionThresholdRatio(0.90d).build())
            .build();

        AgentRunner.builder().chatMemoryProvider(DefaultChatMemory::new).build()
            .addEventListener(events::add)
            .run(agent, "request-reserve", "question");

        AgentContextUsageState state = usageStore.load("request-reserve",
            "request-reserve-agent:1:test:model");
        assertNotNull(state);
        assertFalse(state.isCompressionRequired());
        assertEquals(15L, find(events, AgentEventType.MODEL_COMPLETED)
            .getData().get("reservedOutputTokens"));
    }

    private static Agent agent(String name, ChatModel model,
                               AgentContextCompressionStateStore compressionStore,
                               InMemoryAgentContextUsageStateStore usageStore,
                               AtomicInteger compressions) {
        return Agent.builder(name)
            .chatModel(model)
            .compressionPolicy(AgentContextCompressionPolicy.builder()
                .stateStore(compressionStore)
                .compressor(messages -> {
                    compressions.incrementAndGet();
                    return Collections.<Message>singletonList(new UserMessage("compressed summary"));
                })
                .keepRecentTurns(1)
                .build())
            .contextUsagePolicy(AgentContextUsagePolicy.builder()
                .enabled(true)
                .stateStore(usageStore)
                .compressionThresholdRatio(0.90d)
                .reservedOutputTokens(10)
                .maxTokenLimitRecoveryAttempts(1)
                .build())
            .build();
    }

    private static AgentContextCompressionPolicy compressionPolicy(
        AgentContextCompressionStateStore store, AtomicInteger compressions) {
        return AgentContextCompressionPolicy.builder().stateStore(store)
            .compressor(messages -> {
                compressions.incrementAndGet();
                return Collections.<Message>singletonList(new UserMessage("compressed summary"));
            }).keepRecentTurns(1).build();
    }

    private static UsageModel failingOnce(final TokenLimitExceededException.Phase phase,
                                          final int prompt, final int completion,
                                          final boolean wrapped) {
        return new UsageModel(false, prompt, completion) {
            @Override
            public AiMessageResponse chat(Prompt request, ChatOptions options) {
                incrementCallCount();
                if (getCallCount() == 1) {
                    TokenLimitExceededException limit = new TokenLimitExceededException(
                        "context too long", 400, "context_length_exceeded",
                        "invalid_request", phase);
                    if (wrapped) throw new IllegalStateException("wrapped", limit);
                    throw limit;
                }
                AiMessage message = responseMessage();
                ChatContext context = new ChatContext();
                context.setPrompt(request);
                return new AiMessageResponse(context, null, message);
            }
        };
    }

    private static UsageModel alwaysFailing(final TokenLimitExceededException.Phase phase) {
        return new UsageModel(false, 0, 0) {
            @Override
            public AiMessageResponse chat(Prompt request, ChatOptions options) {
                incrementCallCount();
                throw new TokenLimitExceededException("token limit", 400, "limit",
                    "invalid_request", phase);
            }
        };
    }

    private static DefaultChatMemory history(String id, int turns) {
        DefaultChatMemory memory = new DefaultChatMemory(id);
        for (int index = 0; index < turns; index++) {
            memory.addMessage(new UserMessage("question-" + index));
            memory.addMessage(new AiMessage("answer-" + index));
        }
        return memory;
    }

    private static Tool tool(final String name) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public Parameter[] getParameters() {
                return new Parameter[0];
            }

            @Override
            public Object invoke(Map<String, Object> argsMap) {
                return "found";
            }
        };
    }

    private static void setUsage(AiMessage message, int prompt, int completion) {
        message.setPromptTokens(prompt);
        message.setCompletionTokens(completion);
        message.setTotalTokens(prompt + completion);
    }

    private static boolean containsText(List<Message> messages, String text) {
        for (Message message : messages) {
            if (text.equals(message.getTextContent())) return true;
        }
        return false;
    }

    private static boolean containsType(List<Message> messages, Class<?> type) {
        for (Message message : messages) if (type.isInstance(message)) return true;
        return false;
    }

    private static int indexOf(List<AgentEvent> events, AgentEventType type) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).getType() == type) return index;
        }
        return -1;
    }

    private static class UsageModel implements ChatModel {
        private final boolean failFirst;
        private final int promptTokens;
        private final int completionTokens;
        private int callCount;

        private UsageModel(boolean failFirst, int promptTokens, int completionTokens) {
            this.failFirst = failFirst;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        int getCallCount() {
            return callCount;
        }

        void incrementCallCount() {
            callCount++;
        }

        @Override
        public ChatModelCapabilities getCapabilities() {
            return new ChatModelCapabilities("test:model", 100, 20);
        }

        @Override
        public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
            incrementCallCount();
            if (failFirst && callCount == 1) {
                throw new TokenLimitExceededException("context too long", 400,
                    "context_length_exceeded", "invalid_request",
                    TokenLimitExceededException.Phase.INPUT_CONTEXT);
            }
            AiMessage message = responseMessage();
            ChatContext context = new ChatContext();
            context.setPrompt(prompt);
            return new AiMessageResponse(context, null, message);
        }

        AiMessage responseMessage() {
            AiMessage message = new AiMessage("done");
            setUsage(message, promptTokens, completionTokens);
            return message;
        }

        @Override
        public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static AgentEvent find(List<AgentEvent> events, AgentEventType type) {
        for (AgentEvent event : events) if (event.getType() == type) return event;
        return null;
    }

    private static final class InMemoryCompressionStore
        implements AgentContextCompressionStateStore {
        private AgentContextCompressionState state;

        @Override
        public synchronized AgentContextCompressionState load(String conversationId) {
            return state;
        }

        @Override
        public synchronized boolean save(String conversationId,
                                         AgentContextCompressionState next,
                                         long expectedVersion) {
            long current = state == null ? 0 : state.getVersion();
            if (current != expectedVersion || next.getVersion() != expectedVersion + 1) return false;
            state = next;
            return true;
        }
    }
}
