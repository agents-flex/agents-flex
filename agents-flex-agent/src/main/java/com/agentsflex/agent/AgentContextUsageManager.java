package com.agentsflex.agent;

import com.agentsflex.agent.context.AgentContextCompressionReason;
import com.agentsflex.agent.context.AgentContextUsagePolicy;
import com.agentsflex.agent.context.AgentContextUsageState;
import com.agentsflex.agent.context.AgentContextUsageStateStore;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatModelCapabilities;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.util.StringUtil;

/**
 * 协调 Agent 的服务端 Usage 状态、阈值计算与基准失效。
 *
 * <p>该组件只接受 {@link AiMessage} 中供应商返回的 prompt/completion/total Usage，明确忽略
 * localPromptTokens 等本地字段。其职责是把一次模型响应转换为不可变状态，并通过 CAS Store
 * 解决同一会话并发更新问题。</p>
 */
final class AgentContextUsageManager {

    /**
     * 读取当前 Turn 与实际模型对应的 Usage 状态。
     *
     * @return 策略关闭、Turn 未绑定会话或尚无状态时返回 {@code null}
     */
    AgentContextUsageState load(AgentTurn turn, ChatModel model) {
        AgentContextUsagePolicy policy = turn.getAgent().getContextUsagePolicy();
        if (!isApplicable(turn, policy)) return null;
        return policy.getStateStore().load(turn.getConversationId(), scopeKey(turn, model));
    }

    /**
     * 根据一次成功模型响应记录服务端确认的 Usage。
     *
     * <p>完整性规则如下：</p>
     * <ul>
     *     <li>供应商返回 totalTokens 时即可建立基准；</li>
     *     <li>没有 totalTokens 时，必须同时返回 promptTokens 和 completionTokens；</li>
     *     <li>仅返回 input 或 output 时，不建立新基准，并使旧确认基准失效。</li>
     * </ul>
     *
     * @return 保存后的状态；无法适用或此前也没有基准时返回 {@code null}
     */
    AgentContextUsageState recordConfirmed(AgentTurn turn, ChatModel model, AiMessage message) {
        AgentContextUsagePolicy policy = turn.getAgent().getContextUsagePolicy();
        if (!isApplicable(turn, policy) || message == null) return null;
        Integer prompt = message.getPromptTokens();
        Integer completion = message.getCompletionTokens();
        Integer total = message.getTotalTokens();
        // 不完整 Usage 不能和本地统计拼接，否则会产生看似精确但实际不可验证的基准。
        if (total == null && (prompt == null || completion == null)) {
            return markUsageUnavailable(turn, model);
        }

        // 防御供应商异常负值；total 明确存在时以供应商 total 为准，而不是强制重算。
        long input = prompt == null ? 0 : Math.max(0, prompt);
        long output = completion == null ? 0 : Math.max(0, completion);
        long confirmedTotal = total == null ? input + output : Math.max(0, total);
        long baseline = confirmedTotal > 0 ? confirmedTotal : input + output;
        ChatModelCapabilities capabilities = capabilities(model);
        long usableCapacity = usableInputCapacity(turn, policy, capabilities);
        // 容量未知或输出预留已经占满总容量时，不基于比例触发压缩。
        boolean requireCompression = usableCapacity > 0
            && ((double) baseline / (double) usableCapacity) >= policy.getCompressionThresholdRatio();

        AgentContextUsageStateStore store = policy.getStateStore();
        String scope = scopeKey(turn, model);
        // 短暂 CAS 冲突时重新加载并重算版本；连续三次冲突通常意味着调用方存在异常并发。
        for (int attempt = 0; attempt < 3; attempt++) {
            AgentContextUsageState current = store.load(turn.getConversationId(), scope);
            if (current == null) current = AgentContextUsageState.empty(turn.getConversationId(), scope);
            AgentContextUsageState next = current.confirmed(input, output, confirmedTotal, baseline,
                requireCompression, AgentContextCompressionReason.THRESHOLD_REACHED,
                System.currentTimeMillis());
            if (store.save(next, current.getVersion())) return next;
        }
        throw new IllegalStateException("context usage state changed concurrently");
    }

    /**
     * 后续成功响应没有完整 Usage 时，使此前确认基准失效。
     *
     * <p>若此前从未建立基准，则无需创建一条仅表示“不可用”的持久化记录。</p>
     */
    private AgentContextUsageState markUsageUnavailable(AgentTurn turn, ChatModel model) {
        AgentContextUsagePolicy policy = turn.getAgent().getContextUsagePolicy();
        AgentContextUsageStateStore store = policy.getStateStore();
        String scope = scopeKey(turn, model);
        for (int attempt = 0; attempt < 3; attempt++) {
            AgentContextUsageState current = store.load(turn.getConversationId(), scope);
            if (current == null) return null;
            if (current.getStatus() != com.agentsflex.agent.context.AgentContextUsageStatus.CONFIRMED_BASELINE) {
                return current;
            }
            AgentContextUsageState next = current.usageUnavailable();
            if (store.save(next, current.getVersion())) return next;
        }
        throw new IllegalStateException("context usage state changed concurrently");
    }

    /**
     * 在压缩改变模型可见上下文后使旧 Usage 基准失效。
     *
     * <p>即使此前没有 Usage，也会创建失效状态，用于记录压缩原因和时间。</p>
     */
    AgentContextUsageState invalidate(AgentTurn turn, ChatModel model,
                                      AgentContextCompressionReason reason) {
        AgentContextUsagePolicy policy = turn.getAgent().getContextUsagePolicy();
        if (!isApplicable(turn, policy)) return null;
        AgentContextUsageStateStore store = policy.getStateStore();
        String scope = scopeKey(turn, model);
        for (int attempt = 0; attempt < 3; attempt++) {
            AgentContextUsageState current = store.load(turn.getConversationId(), scope);
            if (current == null) current = AgentContextUsageState.empty(turn.getConversationId(), scope);
            AgentContextUsageState next = current.invalidated(reason, System.currentTimeMillis());
            if (store.save(next, current.getVersion())) return next;
        }
        throw new IllegalStateException("context usage state changed concurrently");
    }

    /**
     * 安全读取模型能力；自定义模型返回 {@code null} 时同样按未知能力处理。
     */
    ChatModelCapabilities capabilities(ChatModel model) {
        if (model == null || model.getCapabilities() == null) return ChatModelCapabilities.unknown();
        return model.getCapabilities();
    }

    /**
     * 计算本次上下文占用展示应预留的输出 Token。
     *
     * <p>优先级为：Usage 策略显式值 &gt; Turn 请求级 maxTokens &gt; Agent 默认 maxTokens
     * &gt; 模型能力中的最大输出 Token。</p>
     */
    long reservedOutputTokens(AgentTurn turn, AgentContextUsagePolicy policy,
                              ChatModelCapabilities capabilities) {
        if (policy.getReservedOutputTokens() > 0) return policy.getReservedOutputTokens();
        ChatOptions options = turn.getChatOptionsOverride();
        if (options == null) options = turn.getAgent().getChatOptions();
        Integer maxTokens = options == null ? null : options.getMaxTokens();
        if (maxTokens != null && maxTokens > 0) return maxTokens;
        return capabilities == null ? 0 : capabilities.getMaxOutputTokens();
    }

    /**
     * 计算可用于输入和历史上下文的容量：总上下文容量减去输出预留。
     *
     * @return 容量未知或预留不小于总容量时返回 0
     */
    long usableInputCapacity(AgentTurn turn, AgentContextUsagePolicy policy,
                             ChatModelCapabilities capabilities) {
        if (capabilities == null || capabilities.getContextWindowTokens() <= 0) return 0;
        return Math.max(0, capabilities.getContextWindowTokens()
            - reservedOutputTokens(turn, policy, capabilities));
    }

    /**
     * 构造 Usage 隔离作用域。模型未提供稳定 key 时使用模型实现类名作为保守回退。
     */
    String scopeKey(AgentTurn turn, ChatModel model) {
        ChatModelCapabilities capabilities = capabilities(model);
        String modelKey = capabilities.getModelKey();
        if (!StringUtil.hasText(modelKey)) {
            modelKey = model == null ? "unknown" : model.getClass().getName();
        }
        return turn.getAgent().getId() + ":" + turn.getAgent().getVersion() + ":" + modelKey;
    }

    /**
     * Usage 管理只适用于显式启用策略且带 conversationId 的持续会话。
     */
    private boolean isApplicable(AgentTurn turn, AgentContextUsagePolicy policy) {
        return turn != null && policy != null && policy.isEnabled()
            && StringUtil.hasText(turn.getConversationId());
    }
}
