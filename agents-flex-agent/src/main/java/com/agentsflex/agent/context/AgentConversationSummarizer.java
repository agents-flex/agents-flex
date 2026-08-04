package com.agentsflex.agent.context;

import com.agentsflex.core.message.Message;

import java.util.List;

/**
 * 将较早的消息转换为可供后续模型调用使用的摘要文本。
 *
 * <p>实现只负责生成摘要，不应直接修改传入消息或 AgentRun。调用可能发生在多个 Run 上，
 * 作为共享组件使用时需要保证线程安全。</p>
 */
@FunctionalInterface
public interface AgentConversationSummarizer {
    /**
     * 汇总一组按对话顺序排列的历史消息。
     *
     * @param messages 即将从模型上下文压缩掉的历史消息
     * @return 可作为后续模型上下文使用的摘要文本
     */
    String summarize(List<Message> messages);
}
