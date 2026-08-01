package com.agentsflex.agent.context;

import com.agentsflex.agent.AgentInvocationContext;
import com.agentsflex.core.message.Message;

import java.util.List;

/** 将较早的消息转换为可供后续模型调用使用的摘要文本。 */
@FunctionalInterface
public interface AgentConversationSummarizer {
    String summarize(List<Message> messages, AgentInvocationContext invocationContext);
}
