/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.StringUtil;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一调用同步或流式 ChatModel，并把流式增量转换为 Agent 实时事件。
 *
 * <p>该组件只负责一次模型请求，不修改 Turn 阶段、消息历史或 Snapshot。模型调用前后的生命周期、
 * 重试和预算处理仍由 AgentRunner 负责。</p>
 */
final class AgentModelInvoker {

    /**
     * Agent 运行时事件发布器，用于把模型返回的文本、推理内容和工具调用增量
     * 转换为可订阅的细粒度事件。
     */
    private final AgentEventPublisher eventPublisher;

    AgentModelInvoker(AgentEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 根据当前 Turn 的 streaming 设置选择模型调用方式。
     */
    AiMessageResponse invoke(AgentTurn turn, Prompt prompt) {
        if (!turn.isStreaming()) {
            return turn.getAgent().getChatModel().chat(prompt, turn.getAgent().getChatOptions());
        }
        return invokeStreaming(turn, prompt);
    }

    /**
     * 等待异步流关闭，并返回与同步接口一致的完整 AiMessageResponse。
     */
    private AiMessageResponse invokeStreaming(AgentTurn turn, Prompt prompt) {
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<AiMessage> fullMessage = new AtomicReference<>();
        AtomicReference<ChatContext> chatContext = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> textDeltaPublished = new AtomicReference<>(false);

        turn.getAgent().getChatModel().chatStream(prompt, new StreamResponseListener() {
            /**
             * 接收模型产生的单个流式帧。
             *
             * <p>普通增量帧会立即发布为运行时事件；最终帧只用于保留完整消息。
             * 如果服务端没有发送任何正文增量，则把最终聚合正文发布一次作为兜底，避免
             * 兼容接口虽然声明 stream=true 但只返回完整正文时丢失用户可见输出。</p>
             */
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                if (context != null) chatContext.set(context.getChatContext());
                AiMessage message = response == null ? null : response.getMessage();
                if (message == null) return;
                if (message.isFinalDelta()) {
                    fullMessage.set(message);
                    String finalContent = StringUtil.hasText(message.getFullContent())
                        ? message.getFullContent() : message.getContent();
                    if (!textDeltaPublished.get() && StringUtil.hasText(finalContent)) {
                        eventPublisher.publish(turn, AgentEventType.MODEL_TEXT_DELTA,
                            data("content", finalContent));
                        textDeltaPublished.set(true);
                    }
                } else if (publishDeltas(turn, message)) {
                    textDeltaPublished.set(true);
                }
            }

            /**
             * 记录流式调用异常并解除等待。
             *
             * <p>部分模型适配器在连接建立前失败时不会继续触发关闭回调，因此此处必须主动
             * 释放等待线程。异常会在等待结束后由调用线程重新抛出。</p>
             */
            @Override
            public void onError(StreamContext context, Throwable error) {
                failure.compareAndSet(null, error);
                // 流在打开前失败时模型实现可能不会再调用 onClose。
                closed.countDown();
            }

            /**
             * 读取流关闭时汇总的完整消息、聊天状态和异常，并通知调用线程继续处理。
             */
            @Override
            public void onClose(StreamContext context) {
                if (context != null) {
                    chatContext.set(context.getChatContext());
                    if (context.getFullMessage() != null) fullMessage.set(context.getFullMessage());
                    if (context.getThrowable() != null) {
                        failure.compareAndSet(null, context.getThrowable());
                    }
                }
                closed.countDown();
            }
        }, turn.getAgent().getChatOptions());

        awaitClose(turn, closed);
        rethrowFailure(failure.get());
        ChatContext context = chatContext.get();
        if (context == null) {
            context = new ChatContext();
            context.setPrompt(prompt);
        }
        return new AiMessageResponse(context, null, fullMessage.get());
    }

    /**
     * 最终完整帧不重复发布正文，只发布真正的增量内容。
     */
    private boolean publishDeltas(AgentTurn turn, AiMessage message) {
        if (message.isFinalDelta()) return false;
        boolean textPublished = false;
        if (StringUtil.hasText(message.getContent())) {
            eventPublisher.publish(turn, AgentEventType.MODEL_TEXT_DELTA,
                data("content", message.getContent()));
            textPublished = true;
        }
        if (StringUtil.hasText(message.getReasoningContent())) {
            eventPublisher.publish(turn, AgentEventType.MODEL_REASONING_DELTA,
                data("content", message.getReasoningContent()));
        }
        if (message.hasToolCalls()) {
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (com.agentsflex.core.message.ToolCall call : message.getToolCalls()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", call.getId());
                item.put("name", call.getName());
                item.put("arguments", call.getArguments());
                toolCalls.add(item);
            }
            eventPublisher.publish(turn, AgentEventType.MODEL_TOOL_CALL_DELTA,
                data("toolCalls", toolCalls));
        }
        return textPublished;
    }

    /**
     * 有总时长预算时，流式等待不会超过当前 Turn 的剩余时间。
     */
    private void awaitClose(AgentTurn turn, CountDownLatch closed) {
        try {
            long maxDuration = turn.getExecutionPolicy().getBudget().getMaxDurationMillis();
            if (maxDuration <= 0) {
                closed.await();
                return;
            }
            long remaining = maxDuration - (System.currentTimeMillis() - turn.getCreatedAt());
            if (remaining <= 0 || !closed.await(remaining, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("streaming model call exceeded maxDurationMillis");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("streaming model call was interrupted", error);
        }
    }

    private void rethrowFailure(Throwable error) {
        if (error == null) return;
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        throw new IllegalStateException("streaming model call failed", error);
    }

    private Map<String, Object> data(String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(key, value);
        return data;
    }
}
