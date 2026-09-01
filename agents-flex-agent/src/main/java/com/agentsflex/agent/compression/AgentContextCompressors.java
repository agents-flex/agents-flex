package com.agentsflex.agent.compression;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.SimplePrompt;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 常用的上下文压缩策略工厂；压缩只生成模型可见视图，不修改 ChatMemory。
 */
public final class AgentContextCompressors {
    /**
     * 工厂类不保存实例状态，禁止构造。
     */
    private AgentContextCompressors() {
    }

    /**
     * 返回输入的独立副本，适合调试或暂时关闭压缩。
     */
    public static AgentContextCompressor identity() {
        return AgentContextCompressors::copy;
    }

    /**
     * 每个完整 Turn 只保留 UserMessage 和最终 AI 正文，不完整 Turn 原样保留。
     */
    public static AgentContextCompressor compactCompletedTurns() {
        return messages -> {
            List<Message> result = new ArrayList<>();
            List<Message> turn = new ArrayList<>();
            for (Message message : messages) {
                if (message instanceof UserMessage && !turn.isEmpty()) {
                    appendCompact(result, turn);
                    turn.clear();
                }
                turn.add(message);
            }
            appendCompact(result, turn);
            return result;
        };
    }

    /**
     * 将历史文本提取为单条用户摘要，并限制字符数，适合作为简单兜底策略。
     */
    public static AgentContextCompressor textExcerpt(int maxCharacters) {
        return textExcerpt(maxCharacters, "历史上下文摘要：");
    }

    /**
     * 将历史文本提取为单条用户摘要，并允许自定义摘要前缀。
     */
    public static AgentContextCompressor textExcerpt(int maxCharacters, String prefix) {
        if (maxCharacters <= 0) throw new IllegalArgumentException("maxCharacters must be greater than 0");
        if (prefix == null) throw new IllegalArgumentException("prefix must not be null");
        return messages -> {
            StringBuilder text = new StringBuilder();
            for (Message message : messages) {
                if (message instanceof UserMessage || message instanceof AiMessage) {
                    if (message.getTextContent() != null) text.append(message.getTextContent()).append('\n');
                }
            }
            String value = text.length() <= maxCharacters ? text.toString() : text.substring(0, maxCharacters);
            return Collections.<Message>singletonList(new UserMessage(prefix + "\n" + value));
        };
    }

    /**
     * 使用聊天模型生成语义摘要。每次压缩只调用一次模型，并将模型返回文本包装为
     * {@code UserMessage + AiMessage}，因此不会把摘要模型的 ToolCall 带入业务模型上下文。
     *
     * @param model       摘要模型，可以与业务模型相同，也可以使用更便宜的专用模型
     * @param instruction 摘要提示词，应要求保留业务事实、ID、约束和未完成事项
     */
    public static AgentContextCompressor model(ChatModel model, String instruction) {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        return model(model, AgentContextModelCompressorOptions.builder()
            .instruction(instruction)
            .build());
    }

    /**
     * 使用配置对象创建整段历史摘要压缩器。
     */
    public static AgentContextCompressor model(
        ChatModel model, AgentContextModelCompressorOptions options) {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        if (options == null) throw new IllegalArgumentException("options must not be null");
        return messages -> {
            StringBuilder input = new StringBuilder(options.getInstruction())
                .append(options.getHistoryHeader());
            for (Message message : messages) {
                input.append(format(options.getModelMessageFormatter(), message));
            }
            AiMessageResponse response = model.chat(new SimplePrompt(input.toString()),
                options.getChatOptions());
            if (response == null || response.isError() || response.getMessage() == null) {
                if (response != null && response.isError()) response.throwIfError();
                throw new IllegalStateException("context summary model returned no message");
            }
            AiMessage summary = response.getMessage().copy();
            summary.setToolCalls(null);
            return java.util.Arrays.<Message>asList(
                new UserMessage(options.getSummaryPrefix()), summary);
        };
    }

    /**
     * 按消息逐条生成摘要，但整个批次只调用一次摘要模型。
     * 模型必须返回 JSON 数组：{@code [{"messageId":"...","summary":"..."}]}。
     * 原消息的角色、messageId 和元数据会保留，只有文本正文被摘要替换。
     * 含 ToolCall 的 AiMessage 或 ToolMessage 会原样保留，避免破坏工具协议。
     */
    public static AgentContextCompressor perMessageModel(ChatModel model, String instruction) {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        return perMessageModel(model, AgentContextModelCompressorOptions.builder()
            .instruction(instruction)
            .build());
    }

    /**
     * 使用配置对象创建逐条消息摘要压缩器。
     */
    public static AgentContextCompressor perMessageModel(
        ChatModel model, AgentContextModelCompressorOptions options) {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        if (options == null) throw new IllegalArgumentException("options must not be null");
        return messages -> {
            StringBuilder input = new StringBuilder(options.getInstruction())
                .append(options.getPerMessageRequest());
            boolean hasCompressibleMessage = false;
            for (Message message : messages) {
                if (message instanceof AiMessage && ((AiMessage) message).hasToolCalls()) continue;
                if (message instanceof com.agentsflex.core.message.ToolMessage) continue;
                String formatted = format(options.getPerMessageFormatter(), message);
                if (message.getMessageId() == null || !formatted.contains(message.getMessageId())) {
                    throw new IllegalStateException(
                        "per-message compression formatter must include messageId");
                }
                input.append(formatted);
                hasCompressibleMessage = true;
            }
            if (!hasCompressibleMessage) {
                return copy(messages);
            }
            AiMessageResponse response = model.chat(new SimplePrompt(input.toString()),
                options.getChatOptions());
            if (response == null || response.isError() || response.getMessage() == null) {
                if (response != null && response.isError()) response.throwIfError();
                throw new IllegalStateException("per-message summary model returned no message");
            }
            String content = response.getMessage().getContent();
            if (content == null || content.trim().isEmpty()) {
                throw new IllegalStateException("per-message summary model returned empty content");
            }
            content = content.trim();
            if (content.startsWith("```")) {
                int firstLine = content.indexOf('\n');
                int lastFence = content.lastIndexOf("```");
                if (firstLine > 0 && lastFence > firstLine) {
                    content = content.substring(firstLine + 1, lastFence).trim();
                }
            }
            JSONArray summaries;
            try {
                summaries = JSON.parseArray(content);
            } catch (RuntimeException e) {
                throw new IllegalStateException("per-message summary model must return a JSON array", e);
            }
            java.util.Map<String, String> byId = new java.util.HashMap<>();
            for (Object value : summaries) {
                if (!(value instanceof JSONObject)) {
                    throw new IllegalStateException(
                        "per-message summary model must return JSON objects");
                }
                JSONObject item = (JSONObject) value;
                String id = item.getString("messageId");
                String summary = item.getString("summary");
                if (id != null && summary != null) byId.put(id, summary);
            }
            List<Message> result = new ArrayList<>();
            for (Message original : messages) {
                String summary = byId.get(original.getMessageId());
                if (summary == null || (original instanceof AiMessage && ((AiMessage) original).hasToolCalls())
                    || original instanceof com.agentsflex.core.message.ToolMessage) {
                    result.add(CompressionMessageUtils.copyMessage(original));
                } else if (original instanceof UserMessage) {
                    UserMessage copy = ((UserMessage) original).copy();
                    copy.setContent(summary);
                    result.add(copy);
                } else if (original instanceof AiMessage) {
                    AiMessage copy = ((AiMessage) original).copy();
                    copy.setContent(summary);
                    copy.setToolCalls(null);
                    result.add(copy);
                } else {
                    result.add(CompressionMessageUtils.copyMessage(original));
                }
            }
            return result;
        };
    }

    private static String format(Function<Message, String> formatter, Message message) {
        String value = formatter.apply(message);
        if (value == null) {
            throw new IllegalStateException("compression message formatter returned null");
        }
        return value;
    }

    /**
     * 按顺序组合多个压缩器，后一个处理前一个的输出。
     */
    public static AgentContextCompressor chain(AgentContextCompressor... compressors) {
        return messages -> {
            List<Message> current = copy(messages);
            if (compressors != null) for (AgentContextCompressor compressor : compressors) {
                if (compressor != null) current = compressor.compress(Collections.unmodifiableList(current));
            }
            return copy(current);
        };
    }

    /**
     * 将单个已完成 Turn 压缩为用户问题和最终 AI 正文；不完整 Turn 原样复制。
     *
     * @param result 追加目标
     * @param turn   待处理完整 Turn
     */
    private static void appendCompact(List<Message> result, List<Message> turn) {
        if (turn.isEmpty()) return;
        Message user = turn.get(0);
        AiMessage answer = null;
        for (int i = turn.size() - 1; i >= 0; i--) {
            if (turn.get(i) instanceof AiMessage) {
                AiMessage ai = (AiMessage) turn.get(i);
                if (!ai.hasToolCalls() && ai.getTextContent() != null) answer = ai;
                break;
            }
        }
        if (user instanceof UserMessage && answer != null) {
            result.add(((UserMessage) user).copy());
            result.add(answer.copy());
        } else result.addAll(copy(turn));
    }

    /**
     * 深复制所有非空消息，空输入返回可修改的空列表。
     */
    private static List<Message> copy(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        if (messages != null) for (Message message : messages) {
            if (message != null) result.add(CompressionMessageUtils.copyMessage(message));
        }
        return result;
    }
}
