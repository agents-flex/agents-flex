package com.agentsflex.agent.compression;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 协调增量压缩、触发判断和业务侧状态 CAS 保存。
 *
 * <p>它只处理已传入的较早历史，不修改 ChatMemory。压缩失败或 CAS 冲突时不会覆盖旧摘要。</p>
 */
final class AgentContextCompressionCoordinator {
    private final AgentContextCompressionStateStore store;
    private final AgentContextCompressionTrigger trigger;
    private final AgentContextCompressor compressor;
    private final ToLongFunction<List<Message>> tokenEstimator;

    /**
     * 创建增量压缩协调器，并要求状态存储、触发器、压缩器和 Token 估算器全部可用。
     *
     * @param store          按会话持久化压缩游标和摘要的 CAS Store
     * @param trigger        判断当前增量是否值得压缩的策略
     * @param compressor     生成新摘要的实现
     * @param tokenEstimator 对待压缩消息进行非负 Token 估算的函数
     */
    AgentContextCompressionCoordinator(AgentContextCompressionStateStore store,
                                       AgentContextCompressionTrigger trigger,
                                       AgentContextCompressor compressor,
                                       ToLongFunction<List<Message>> tokenEstimator) {
        if (store == null || trigger == null || compressor == null || tokenEstimator == null) {
            throw new IllegalArgumentException("compression coordinator dependencies must not be null");
        }
        this.store = store;
        this.trigger = trigger;
        this.compressor = compressor;
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 对一段完整、按时间升序排列的可压缩历史执行一次增量压缩。
     * <p>调用方应在这里传入已排除当前 Turn 和最近保护 Turn 的历史；协调器不会修改输入，
     * 也不会操作 ChatMemory。每次传入仍须包含已持久化游标对应的消息。</p>
     */
    AgentContextCompressionResult compress(String conversationId,
                                           List<Message> chronologicalMessages) {
        if (conversationId == null || chronologicalMessages == null) {
            throw new IllegalArgumentException("conversationId and messages must not be null");
        }
        AgentContextCompressionState state = store.load(conversationId);
        if (state == null) state = AgentContextCompressionState.empty();
        List<Message> pending = pendingAfter(state.getCoveredUntilMessageId(), chronologicalMessages);
        long estimatedTokens = tokenEstimator.applyAsLong(pending);
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("tokenEstimator must return a non-negative value");
        }
        int pendingTurnCount = countTurns(pending);
        AgentContextCompressionInput input = new AgentContextCompressionInput(
            pending, state.getSummaryMessages(), estimatedTokens, pendingTurnCount, state);
        if (pending.isEmpty() || !trigger.shouldCompress(input)) {
            List<Message> modelMessages = new ArrayList<>(state.getSummaryMessages());
            modelMessages.addAll(pending);
            return new AgentContextCompressionResult(false, state, modelMessages);
        }

        List<Message> compressorInput = new ArrayList<>(state.getSummaryMessages());
        compressorInput.addAll(pending);
        List<Message> summary = compressor.compress(compressorInput);
        if (summary == null || summary.isEmpty()) {
            throw new IllegalStateException("contextCompressor returned no summary");
        }
        Message last = pending.get(pending.size() - 1);
        AgentContextCompressionState next = new AgentContextCompressionState(
            state.getVersion() + 1, summary, last.getMessageId());
        if (!store.save(conversationId, next, state.getVersion())) {
            throw new IllegalStateException("compression state changed concurrently");
        }
        return new AgentContextCompressionResult(true, next, summary);
    }

    /**
     * 根据持久化游标截取尚未进入摘要的消息。
     *
     * @param coveredId 上次已覆盖的最后消息 ID；首次压缩时为空
     * @param messages  包含游标消息的完整正序历史
     * @return 游标之后消息的新列表
     * @throws IllegalStateException 游标不在传入历史中时抛出
     */
    private static List<Message> pendingAfter(String coveredId, List<Message> messages) {
        int start = 0;
        if (coveredId != null) {
            boolean found = false;
            for (int i = 0; i < messages.size(); i++) {
                if (coveredId.equals(messages.get(i).getMessageId())) {
                    start = i + 1;
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalStateException("compression state references message not present in chronological history: "
                    + coveredId);
            }
        }
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    /**
     * 根据 UserMessage 数量估算给定消息覆盖的 Turn 数。
     *
     * @param messages 待统计的正序消息
     * @return Turn 数量
     */
    private static int countTurns(List<Message> messages) {
        int count = 0;
        for (Message message : messages) if (message instanceof UserMessage) count++;
        return count;
    }

}
