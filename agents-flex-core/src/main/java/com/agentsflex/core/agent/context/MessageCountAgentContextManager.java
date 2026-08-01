package com.agentsflex.core.agent.context;

import com.agentsflex.core.agent.AgentInvocationContext;
import com.agentsflex.core.agent.AgentRun;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/** 超过消息数量上限时，将较早历史压缩为一条摘要消息。 */
public final class MessageCountAgentContextManager implements AgentContextManager {
    private final int maxMessages;
    private final int recentMessages;
    private final AgentConversationSummarizer summarizer;

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
