package com.agentsflex.agent;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 协调增量压缩、触发判断和业务侧状态 CAS 保存。
 *
 * <p>它只处理已传入的较早历史，不修改 ChatMemory。压缩失败或 CAS 冲突时不会覆盖旧摘要。</p>
 */
public final class AgentContextCompressionCoordinator {
    private final AgentContextCompressionStateStore store;
    private final AgentContextCompressionTrigger trigger;
    private final AgentContextCompressor compressor;
    private final ToLongFunction<List<Message>> tokenEstimator;

    public AgentContextCompressionCoordinator(AgentContextCompressionStateStore store,
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
    public AgentContextCompressionResult compress(String conversationId, List<Message> chronologicalMessages) {
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
        AgentContextCompressionInput input = new AgentContextCompressionInput(
            pending, state.getSummaryMessages(), estimatedTokens, countTurns(pending), state);
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
            state.getVersion() + 1, summary, last.getMessageId(),
            state.getCompressionVersion() + 1,
            saturatingAdd(state.getEstimatedCoveredTokens(), estimatedTokens),
            saturatingAdd(state.getCoveredTurnCount(), countTurns(pending)));
        if (!store.save(conversationId, next, state.getVersion())) {
            throw new IllegalStateException("compression state changed concurrently");
        }
        return new AgentContextCompressionResult(true, next, summary);
    }

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

    private static int countTurns(List<Message> messages) {
        int count = 0;
        for (Message message : messages) if (message instanceof UserMessage) count++;
        return count;
    }

    private static int saturatingAdd(int left, int right) {
        if (right > 0 && left > Integer.MAX_VALUE - right) return Integer.MAX_VALUE;
        return left + right;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
