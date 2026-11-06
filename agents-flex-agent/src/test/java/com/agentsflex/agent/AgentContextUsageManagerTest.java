package com.agentsflex.agent;

import com.agentsflex.agent.compression.AgentContextCompressionPolicy;
import com.agentsflex.agent.compression.AgentContextCompressionState;
import com.agentsflex.agent.compression.AgentContextCompressionStateStore;
import com.agentsflex.agent.context.AgentContextCompressionReason;
import com.agentsflex.agent.context.AgentContextUsagePolicy;
import com.agentsflex.agent.context.AgentContextUsageState;
import com.agentsflex.agent.context.AgentContextUsageStateStore;
import com.agentsflex.agent.context.AgentContextUsageStatus;
import com.agentsflex.agent.context.InMemoryAgentContextUsageStateStore;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatModelCapabilities;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.Prompt;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

/**
 * 验证 Usage Manager 的完整性判断、容量公式、状态转换、作用域和 CAS 冲突处理。
 */
public class AgentContextUsageManagerTest {

    /**
     * 被测状态协调器。
     */
    private final AgentContextUsageManager manager = new AgentContextUsageManager();

    /**
     * 验证仅返回 totalTokens 时也可建立服务端确认基准。
     */
    @Test
    public void totalOnlyUsageMustCreateProviderConfirmedBaseline() {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        StubModel model = new StubModel("provider:model", 100, 10);
        AgentTurn turn = turn(agent("agent", model, policy(store, 0)), "conversation", null);
        AiMessage message = new AiMessage("done");
        message.setTotalTokens(90);

        AgentContextUsageState state = manager.recordConfirmed(turn, model, message);

        assertEquals(0, state.getPromptTokens());
        assertEquals(0, state.getCompletionTokens());
        assertEquals(90, state.getTotalTokens());
        assertEquals(90, state.getNextCallBaselineTokens());
        assertTrue(state.isCompressionRequired());
    }

    /**
     * 验证缺少 totalTokens 时由完整 input/output 相加形成总量。
     */
    @Test
    public void promptAndCompletionMustBuildTotalWhenProviderOmitsIt() {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        StubModel model = new StubModel("provider:model", 100, 0);
        AgentTurn turn = turn(agent("agent", model, policy(store, 0)), "conversation", null);
        AiMessage message = new AiMessage("done");
        message.setPromptTokens(30);
        message.setCompletionTokens(5);

        AgentContextUsageState state = manager.recordConfirmed(turn, model, message);

        assertEquals(35, state.getTotalTokens());
        assertEquals(35, state.getNextCallBaselineTokens());
        assertFalse(state.isCompressionRequired());
    }

    /**
     * 验证供应商 totalTokens 优先于 input/output 的本地相加结果。
     */
    @Test
    public void providerTotalMustTakePrecedenceOverPromptAndCompletionSum() {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        StubModel model = new StubModel("provider:model", 100, 0);
        AgentTurn turn = turn(agent("agent", model, policy(store, 0)), "conversation", null);
        AiMessage message = usage(90, 20, 50);

        AgentContextUsageState state = manager.recordConfirmed(turn, model, message);

        assertEquals(90, state.getPromptTokens());
        assertEquals(20, state.getCompletionTokens());
        assertEquals(50, state.getTotalTokens());
        assertEquals(50, state.getNextCallBaselineTokens());
        assertFalse(state.isCompressionRequired());
    }

    /**
     * 验证阈值采用大于等于语义，临界值必须触发压缩。
     */
    @Test
    public void thresholdComparisonMustIncludeExactBoundaryButExcludeJustBelow() {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        StubModel model = new StubModel("provider:model", 100, 10);
        AgentTurn turn = turn(agent("agent", model, policy(store, 0)), "conversation", null);

        AgentContextUsageState below = manager.recordConfirmed(turn, model, usage(80, 0, 80));
        assertFalse(below.isCompressionRequired());
        AgentContextUsageState exact = manager.recordConfirmed(turn, model, usage(81, 0, 81));
        assertTrue(exact.isCompressionRequired());
        assertEquals(AgentContextCompressionReason.THRESHOLD_REACHED, exact.getCompressionReason());
    }

    /**
     * 验证异常负数 Usage 被防御性归零。
     */
    @Test
    public void negativeProviderCountersMustBeClampedToZero() {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        StubModel model = new StubModel("provider:model", 100, 0);
        AgentTurn turn = turn(agent("agent", model, policy(store, 0)), "conversation", null);
        AiMessage message = new AiMessage("done");
        message.setPromptTokens(-10);
        message.setCompletionTokens(-2);
        message.setTotalTokens(-12);

        AgentContextUsageState state = manager.recordConfirmed(turn, model, message);

        assertEquals(0, state.getPromptTokens());
        assertEquals(0, state.getCompletionTokens());
        assertEquals(0, state.getTotalTokens());
        assertEquals(0, state.getNextCallBaselineTokens());
        assertFalse(state.isCompressionRequired());
    }

    /**
     * 验证不完整 Usage 只会使已经存在的确认基准失效。
     */
    @Test
    public void incompleteUsageMustInvalidateOnlyAnExistingConfirmedBaseline() {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        StubModel model = new StubModel("provider:model", 100, 0);
        AgentTurn turn = turn(agent("agent", model, policy(store, 0)), "conversation", null);
        AiMessage partial = new AiMessage("done");
        partial.setPromptTokens(10);
        assertNull(manager.recordConfirmed(turn, model, partial));

        AgentContextUsageState confirmed = AgentContextUsageState.empty(
                "conversation", "agent:1:provider:model")
            .confirmed(10, 2, 12, 12, false, null, 1);
        assertTrue(store.save(confirmed, 0));
        AgentContextUsageState invalidated = manager.recordConfirmed(turn, model, partial);
        assertEquals(AgentContextUsageStatus.BASELINE_INVALIDATED, invalidated.getStatus());
        assertEquals(2, invalidated.getVersion());

        AgentContextUsageState unchanged = manager.recordConfirmed(turn, model, partial);
        assertSame(invalidated, unchanged);
        assertEquals(2, unchanged.getVersion());
    }

    /**
     * 验证输出预留优先级：策略、Turn、Agent、模型能力。
     */
    @Test
    public void reservedOutputPriorityMustBePolicyThenTurnThenAgentThenModel() {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        StubModel model = new StubModel("provider:model", 100, 40);

        ChatOptions agentOptions = options(30);
        Agent policyAgent = agent("policy", model, policy(store, 10), agentOptions);
        AgentTurn policyTurn = turn(policyAgent, "c1", options(20));
        assertEquals(10, manager.reservedOutputTokens(policyTurn, policyAgent.getContextUsagePolicy(),
            model.getCapabilities()));

        Agent turnAgent = agent("turn", model, policy(store, 0), agentOptions);
        AgentTurn turnOverride = turn(turnAgent, "c2", options(20));
        assertEquals(20, manager.reservedOutputTokens(turnOverride, turnAgent.getContextUsagePolicy(),
            model.getCapabilities()));

        AgentTurn agentDefault = turn(turnAgent, "c3", null);
        assertEquals(30, manager.reservedOutputTokens(agentDefault, turnAgent.getContextUsagePolicy(),
            model.getCapabilities()));

        Agent modelAgent = agent("model", model, policy(store, 0));
        AgentTurn modelDefault = turn(modelAgent, "c4", null);
        assertEquals(40, manager.reservedOutputTokens(modelDefault, modelAgent.getContextUsagePolicy(),
            model.getCapabilities()));
    }

    /**
     * 验证可用输入容量不会为负，未知窗口返回 0。
     */
    @Test
    public void usableCapacityMustFloorAtZeroAndRemainUnknownWithoutContextWindow() {
        AgentContextUsagePolicy policy = policy(new InMemoryAgentContextUsageStateStore(), 120);
        StubModel small = new StubModel("small", 100, 0);
        AgentTurn turn = turn(agent("agent", small, policy), "conversation", null);
        assertEquals(0, manager.usableInputCapacity(turn, policy, small.getCapabilities()));
        assertEquals(0, manager.usableInputCapacity(turn, policy, ChatModelCapabilities.unknown()));
        assertEquals(0, manager.usableInputCapacity(turn, policy, null));
    }

    /**
     * 验证作用域包含 Agent 版本，模型 key 缺失时回退到类名。
     */
    @Test
    public void scopeMustUseAgentVersionAndFallBackToModelClass() {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        StubModel model = new StubModel(null, 0, 0);
        Agent agent = Agent.builder("display").id("stable").version("v2")
            .chatModel(model).compressionPolicy(compressionPolicy())
            .contextUsagePolicy(policy(store, 0)).build();
        AgentTurn turn = turn(agent, "conversation", null);

        assertEquals("stable:v2:" + StubModel.class.getName(), manager.scopeKey(turn, model));
        assertSame(ChatModelCapabilities.unknown(), manager.capabilities(null));
    }

    /**
     * 验证策略关闭或 Turn 未绑定会话时完全不读写状态。
     */
    @Test
    public void disabledOrUnboundTurnMustNotReadOrWriteUsageState() {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        StubModel model = new StubModel("provider:model", 100, 0);
        Agent disabled = Agent.builder("disabled").chatModel(model).build();
        AgentTurn disabledTurn = AgentTurn.start(disabled, "question");
        assertNull(manager.load(disabledTurn, model));
        assertNull(manager.recordConfirmed(disabledTurn, model, usage(1, 1, 2)));

        Agent enabled = agent("enabled", model, policy(store, 0));
        AgentTurn unbound = AgentTurn.start(enabled, "question");
        assertNull(manager.load(unbound, model));
        assertNull(manager.invalidate(unbound, model, AgentContextCompressionReason.MANUAL));
    }

    /**
     * 验证没有旧 Usage 时，压缩仍会创建可审计的失效状态。
     */
    @Test
    public void invalidateMustCreateAuditStateEvenWithoutPreviousUsage() {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        StubModel model = new StubModel("provider:model", 100, 0);
        AgentTurn turn = turn(agent("agent", model, policy(store, 0)), "conversation", null);

        AgentContextUsageState state = manager.invalidate(turn, model,
            AgentContextCompressionReason.POLICY_TRIGGERED);

        assertEquals(1, state.getVersion());
        assertEquals(AgentContextUsageStatus.BASELINE_INVALIDATED, state.getStatus());
        assertEquals(AgentContextCompressionReason.POLICY_TRIGGERED, state.getCompressionReason());
        assertTrue(state.getLastCompressionAt() > 0);
    }

    /**
     * 验证连续 CAS 冲突超过内部重试次数后明确失败。
     */
    @Test
    public void repeatedCasConflictsMustFailDeterministically() {
        AgentContextUsageStateStore conflicts = new AgentContextUsageStateStore() {
            @Override
            public AgentContextUsageState load(String conversationId, String usageScopeKey) {
                return null;
            }

            @Override
            public boolean save(AgentContextUsageState state, long expectedVersion) {
                return false;
            }
        };
        StubModel model = new StubModel("provider:model", 100, 0);
        AgentTurn turn = turn(agent("agent", model, policy(conflicts, 0)), "conversation", null);

        try {
            manager.recordConfirmed(turn, model, usage(1, 1, 2));
            fail("Expected concurrent state failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("changed concurrently"));
        }
    }

    /**
     * 验证启用 Usage 策略时 Agent 必须配置持久化压缩能力。
     */
    @Test
    public void enabledUsageMustRequirePersistentCompressionPolicyAtAgentBuildTime() {
        StubModel model = new StubModel("provider:model", 100, 0);
        try {
            Agent.builder("invalid").chatModel(model)
                .contextUsagePolicy(policy(new InMemoryAgentContextUsageStateStore(), 0))
                .build();
            fail("Expected persistent compression policy validation");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("persistent compressionPolicy"));
        }
    }

    private static AgentContextUsagePolicy policy(AgentContextUsageStateStore store, long reserve) {
        return AgentContextUsagePolicy.builder().enabled(true).stateStore(store)
            .compressionThresholdRatio(0.90d).reservedOutputTokens(reserve).build();
    }

    private static Agent agent(String id, ChatModel model, AgentContextUsagePolicy policy) {
        return agent(id, model, policy, null);
    }

    private static Agent agent(String id, ChatModel model, AgentContextUsagePolicy policy,
                               ChatOptions options) {
        Agent.Builder builder = Agent.builder(id).chatModel(model)
            .compressionPolicy(compressionPolicy()).contextUsagePolicy(policy);
        if (options != null) builder.chatOptions(options);
        return builder.build();
    }

    private static AgentContextCompressionPolicy compressionPolicy() {
        return AgentContextCompressionPolicy.builder().stateStore(new CompressionStore())
            .compressor(messages -> Collections.<Message>singletonList(new AiMessage("summary")))
            .build();
    }

    private static AgentTurn turn(Agent agent, String conversationId, ChatOptions override) {
        AgentTurnOptions.Builder options = AgentTurnOptions.builder();
        if (override != null) options.chatOptions(override);
        AgentTurn turn = AgentTurn.start(agent, "question", options.build());
        turn.bindConversation(conversationId, 0);
        return turn;
    }

    private static ChatOptions options(int maxTokens) {
        ChatOptions options = new ChatOptions();
        options.setMaxTokens(maxTokens);
        return options;
    }

    private static AiMessage usage(int prompt, int completion, int total) {
        AiMessage message = new AiMessage("done");
        message.setPromptTokens(prompt);
        message.setCompletionTokens(completion);
        message.setTotalTokens(total);
        return message;
    }

    private static final class CompressionStore implements AgentContextCompressionStateStore {
        @Override
        public AgentContextCompressionState load(String conversationId) {
            return null;
        }

        @Override
        public boolean save(String conversationId, AgentContextCompressionState next,
                            long expectedVersion) {
            return true;
        }
    }

    private static final class StubModel implements ChatModel {
        private final ChatModelCapabilities capabilities;

        private StubModel(String key, long context, long output) {
            capabilities = new ChatModelCapabilities(key, context, output);
        }

        @Override
        public ChatModelCapabilities getCapabilities() {
            return capabilities;
        }

        @Override
        public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void chatStream(Prompt prompt, StreamResponseListener listener,
                               ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }
}
