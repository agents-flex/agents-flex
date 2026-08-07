package com.agentsflex.agent;

import com.agentsflex.core.message.Message;

import java.util.List;

/**
 * 可选的语义上下文压缩器。
 *
 * <p>输入是较早、已经完成且允许压缩的历史消息；返回值是模型可见的归一化消息。压缩器不应修改
 * 输入消息，也不应删除业务 ChatMemory 中的原始历史。返回结果可以从 {@code SystemMessage} 开始，
 * 但其后必须是 {@code UserMessage}，
 * 并保持 ToolCall 与 ToolMessage 的完整配对。</p>
 */
@FunctionalInterface
public interface AgentContextCompressor {

    List<Message> compress(List<Message> messages);
}
