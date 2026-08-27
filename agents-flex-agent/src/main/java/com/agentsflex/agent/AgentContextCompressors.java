package com.agentsflex.agent;

import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.SimplePrompt;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 常用的本地上下文压缩策略工厂；不调用模型，也不修改 ChatMemory。
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
        if (maxCharacters <= 0) throw new IllegalArgumentException("maxCharacters must be greater than 0");
        return messages -> {
            StringBuilder text = new StringBuilder();
            for (Message message : messages) {
                if (message instanceof UserMessage || message instanceof AiMessage) {
                    if (message.getTextContent() != null) text.append(message.getTextContent()).append('\n');
                }
            }
            String value = text.length() <= maxCharacters ? text.toString() : text.substring(0, maxCharacters);
            return Collections.<Message>singletonList(new UserMessage("历史上下文摘要：\n" + value));
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
        if (instruction == null || instruction.trim().isEmpty()) {
            throw new IllegalArgumentException("instruction must not be blank");
        }
        return messages -> {
            StringBuilder input = new StringBuilder(instruction).append("\n\n历史消息：\n");
            for (Message message : messages) {
                String text = message.getTextContent();
                if (text != null && !text.isEmpty()) {
                    input.append(message.getClass().getSimpleName()).append(": ").append(text).append('\n');
                }
            }
            AiMessageResponse response = model.chat(new SimplePrompt(input.toString()), new ChatOptions());
            if (response == null || response.isError() || response.getMessage() == null) {
                if (response != null && response.isError()) response.throwIfError();
                throw new IllegalStateException("context summary model returned no message");
            }
            AiMessage summary = response.getMessage().copy();
            summary.setToolCalls(null);
            return java.util.Arrays.<Message>asList(
                new UserMessage("以下是较早对话的摘要，请将其作为历史事实参考："), summary);
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
        if (instruction == null || instruction.trim().isEmpty()) {
            throw new IllegalArgumentException("instruction must not be blank");
        }
        return messages -> {
            StringBuilder input = new StringBuilder(instruction)
                .append("\n请逐条摘要以下消息，并仅返回 JSON 数组，每项包含 messageId 和 summary：\n");
            for (Message message : messages) {
                if (message instanceof AiMessage && ((AiMessage) message).hasToolCalls()) continue;
                if (message instanceof com.agentsflex.core.message.ToolMessage) continue;
                input.append("messageId=").append(message.getMessageId())
                    .append(", role=").append(message.getClass().getSimpleName())
                    .append(", content=").append(message.getTextContent()).append('\n');
            }
            AiMessageResponse response = model.chat(new SimplePrompt(input.toString()), new ChatOptions());
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
                    result.add(AgentMessageUtils.copyMessage(original));
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
                    result.add(AgentMessageUtils.copyMessage(original));
                }
            }
            return result;
        };
    }

    /**
     * 创建一个增量语义压缩器。状态对象应由业务侧随 conversation 一起持久化，不能只保存在
     * Runner 的内存中；这样服务重启后仍能从上次覆盖的位置继续摘要。
     */
    public static Incremental incremental(ChatModel model, String instruction) {
        return new Incremental(model(model, instruction));
    }

    /**
     * 使用自定义摘要器创建增量状态，便于接入业务摘要服务或测试替身。
     */
    public static Incremental incremental(AgentContextCompressor compressor) {
        if (compressor == null) throw new IllegalArgumentException("compressor must not be null");
        return new Incremental(compressor);
    }

    /**
     * 整体摘要的增量更新器：适合历史很长、允许用一条“事实摘要”代表较早对话的场景。
     * 它不是逐消息摘要策略；业务侧应持久化 summary 和 coveredUntilMessageId，避免服务重启后重复处理。
     */
    public static final class Incremental implements AgentContextCompressor {
        private final AgentContextCompressor delegate;
        private List<Message> summary = new ArrayList<>();
        private String coveredUntilMessageId;

        /**
         * @param delegate 负责重新摘要“旧摘要 + 新增消息”的实际压缩器
         */
        private Incremental(AgentContextCompressor delegate) {
            this.delegate = delegate;
        }

        /**
         * 只把尚未覆盖的消息与既有摘要合并后交给摘要模型；同一批消息重复调用不会重复请求模型。
         */
        @Override
        public synchronized List<Message> compress(List<Message> messages) {
            if (messages == null || messages.isEmpty()) return copy(summary);
            int start = 0;
            if (coveredUntilMessageId != null) {
                for (int i = 0; i < messages.size(); i++) {
                    if (coveredUntilMessageId.equals(messages.get(i).getMessageId())) {
                        start = i + 1;
                        break;
                    }
                }
            }
            if (start >= messages.size()) return copy(summary);
            List<Message> input = new ArrayList<>(summary);
            input.addAll(messages.subList(start, messages.size()));
            List<Message> result = delegate.compress(Collections.unmodifiableList(copy(input)));
            summary = copy(result);
            coveredUntilMessageId = messages.get(messages.size() - 1).getMessageId();
            return copy(summary);
        }

        public synchronized List<Message> getSummary() {
            return copy(summary);
        }

        public synchronized String getCoveredUntilMessageId() {
            return coveredUntilMessageId;
        }

        /**
         * 从业务持久化状态恢复摘要和覆盖游标，并复制摘要消息。
         *
         * @param summary               已保存摘要
         * @param coveredUntilMessageId 已覆盖的最后消息 ID
         */
        public synchronized void restore(List<Message> summary, String coveredUntilMessageId) {
            this.summary = copy(summary);
            this.coveredUntilMessageId = coveredUntilMessageId;
        }
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
            if (message != null) result.add(AgentMessageUtils.copyMessage(message));
        }
        return result;
    }
}
