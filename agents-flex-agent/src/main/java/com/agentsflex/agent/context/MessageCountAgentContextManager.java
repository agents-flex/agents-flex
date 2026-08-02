package com.agentsflex.agent.context;

import com.agentsflex.agent.AgentInvocationContext;
import com.agentsflex.agent.AgentRun;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 超过消息数量上限时，将较早历史压缩为一条摘要消息。
 *
 * <p>实现始终保留最近的指定数量消息，并避免从 ToolMessage 中间切断一组模型工具交互。摘要以带有
 * {@code agent.context.summary} 元数据的 UserMessage 写回 Memory，因此压缩结果会进入 Checkpoint。</p>
 */
public final class MessageCountAgentContextManager implements AgentContextManager {
    /**
     * 触发压缩的非系统消息数量阈值。
     */
    private final int maxMessages;
    /**
     * 每次压缩必须原样保留的最近消息数量。
     */
    private final int recentMessages;
    /**
     * 把较早消息转换为摘要文本的应用实现。
     */
    private final AgentConversationSummarizer summarizer;

    /**
     * 创建按消息数量触发的上下文管理器。
     *
     * @param maxMessages    超过该数量时触发压缩，至少为 2
     * @param recentMessages 原样保留的最近消息数，必须小于 maxMessages
     * @param summarizer     生成摘要文本的实现
     */
    public MessageCountAgentContextManager(int maxMessages, int recentMessages,
                                           AgentConversationSummarizer summarizer) {
        if (maxMessages < 2 || recentMessages < 1 || recentMessages >= maxMessages
            || summarizer == null) {
            throw new IllegalArgumentException("invalid context compaction configuration");
        }
        this.maxMessages = maxMessages;
        this.recentMessages = recentMessages;
        this.summarizer = summarizer;
    }

    /**
     * 在模型调用前检查并原子替换 Run 的 Memory 消息。
     */
    @Override
    public AgentContextUpdate prepare(AgentRun run, AgentInvocationContext invocationContext) {
        List<Message> all = run.getPrompt().getMemory().getMessages(Integer.MAX_VALUE);
        List<Message> messages = new ArrayList<>();
        for (Message message : all) if (!(message instanceof SystemMessage)) messages.add(message);
        if (messages.size() <= maxMessages) return AgentContextUpdate.unchanged();

        int cut = messages.size() - recentMessages;
        while (cut > 0 && messages.get(cut) instanceof ToolMessage) cut--;
        if (cut <= 0) return AgentContextUpdate.unchanged();

        List<Message> older = new ArrayList<>(messages.subList(0, cut));
        String summaryText = summarizer.summarize(older, invocationContext);
        if (summaryText == null) return AgentContextUpdate.unchanged();

        UserMessage summary = new UserMessage("Conversation summary:\n" + summaryText);
        summary.putMetadata("agent.context.summary", true);
        List<Message> compacted = new ArrayList<>();
        compacted.add(summary);
        compacted.addAll(messages.subList(cut, messages.size()));
        run.getPrompt().getMemory().clear();
        run.getPrompt().getMemory().addMessages(compacted);
        return new AgentContextUpdate(true, cut, compacted.size());
    }
}
