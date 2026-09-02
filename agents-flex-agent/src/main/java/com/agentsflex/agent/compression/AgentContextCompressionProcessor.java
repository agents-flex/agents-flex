package com.agentsflex.agent.compression;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 处理增量压缩、条件判断和业务侧状态 CAS 保存。
 *
 * <p>它只处理已传入的较早历史，不修改 ChatMemory。压缩失败或 CAS 冲突时不会覆盖旧摘要。</p>
 */
final class AgentContextCompressionProcessor {
    private final AgentContextCompressionStateStore store;
    private final AgentContextCompressionDecider decider;
    private final AgentContextCompressor compressor;
    private final ToLongFunction<List<Message>> tokenEstimator;
    private final AgentCompressionFailureStrategy compressionFailureStrategy;

    /**
     * 创建增量压缩处理器，并要求状态存储、决策器、压缩器和 Token 估算器全部可用。
     *
     * @param store          按会话持久化压缩游标和摘要的 CAS Store
     * @param decider        判断当前增量是否值得压缩的决策器
     * @param compressor     生成新摘要的实现
     * @param tokenEstimator 对待压缩消息进行非负 Token 估算的函数
     */
    AgentContextCompressionProcessor(AgentContextCompressionStateStore store,
                                     AgentContextCompressionDecider decider,
                                     AgentContextCompressor compressor,
                                     ToLongFunction<List<Message>> tokenEstimator) {
        this(store, decider, compressor, tokenEstimator, AgentCompressionFailureStrategy.FAIL);
    }

    /**
     * 创建带压缩失败策略的增量压缩处理器。
     *
     * <p>失败策略只包围压缩器本身的调用。状态读取、游标校验和 CAS 保存属于一致性边界，
     * 即使启用了回退也不能吞掉这些错误。</p>
     */
    AgentContextCompressionProcessor(AgentContextCompressionStateStore store,
                                     AgentContextCompressionDecider decider,
                                     AgentContextCompressor compressor,
                                     ToLongFunction<List<Message>> tokenEstimator,
                                     AgentCompressionFailureStrategy compressionFailureStrategy) {
        if (store == null || decider == null || compressor == null || tokenEstimator == null) {
            throw new IllegalArgumentException("compression processor dependencies must not be null");
        }
        this.store = store;
        this.decider = decider;
        this.compressor = compressor;
        this.tokenEstimator = tokenEstimator;
        this.compressionFailureStrategy = compressionFailureStrategy == null
            ? AgentCompressionFailureStrategy.FAIL : compressionFailureStrategy;
    }

    /**
     * 对一段完整、按时间升序排列的可压缩历史执行一次增量压缩。
     * <p>调用方应在这里传入已排除当前 Turn 和最近保护 Turn 的历史；处理器不会修改输入，
     * 也不会操作 ChatMemory。每次传入仍须包含已持久化游标对应的消息。</p>
     */
    AgentContextCompressionResult process(String conversationId,
                                          List<Message> chronologicalMessages) {
        return process(conversationId, chronologicalMessages, null);
    }

    /**
     * 对一段历史执行增量压缩，并在决策器确认需要压缩、即将调用压缩器时通知调用方。
     *
     * @param conversationId        会话 ID
     * @param chronologicalMessages 按时间升序排列的历史消息
     * @param onCompressionStarted  真正开始调用压缩器前执行的回调，可为空
     * @return 压缩结果
     */
    AgentContextCompressionResult process(String conversationId,
                                          List<Message> chronologicalMessages,
                                          Runnable onCompressionStarted) {
        if (conversationId == null || conversationId.trim().isEmpty() || chronologicalMessages == null) {
            throw new IllegalArgumentException("conversationId must not be blank and messages must not be null");
        }
        for (Message message : chronologicalMessages) {
            if (message == null) {
                throw new IllegalArgumentException("chronological history must not contain null messages");
            }
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
        if (pending.isEmpty() || !decider.shouldCompress(input)) {
            List<Message> modelMessages = new ArrayList<>(state.getSummaryMessages());
            modelMessages.addAll(pending);
            return new AgentContextCompressionResult(false, state, modelMessages);
        }

        if (onCompressionStarted != null) onCompressionStarted.run();
        List<Message> compressorInput = new ArrayList<>(state.getSummaryMessages());
        compressorInput.addAll(pending);
        List<Message> summary;
        try {
            summary = compressor.compress(Collections.unmodifiableList(copyMessages(compressorInput)));
            AgentContextCompressionValidator.validate(summary, true);
        } catch (RuntimeException error) {
            if (compressionFailureStrategy == AgentCompressionFailureStrategy.USE_ORIGINAL) {
                return originalResult(state, pending);
            }
            throw error;
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
     * 压缩器失败时保留旧状态和完整 pending，保证下一次调用仍可重试同一批消息。
     */
    private static AgentContextCompressionResult originalResult(AgentContextCompressionState state,
                                                                List<Message> pending) {
        List<Message> modelMessages = new ArrayList<>(state.getSummaryMessages());
        modelMessages.addAll(pending);
        return new AgentContextCompressionResult(false, state, modelMessages);
    }

    private static List<Message> copyMessages(List<Message> messages) {
        List<Message> copies = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message != null) copies.add(CompressionMessageUtils.copyMessage(message));
        }
        return copies;
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
