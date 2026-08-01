/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.core.agent;

import com.agentsflex.core.agent.event.AgentRuntimeEventStream;
import com.agentsflex.core.agent.event.AgentRuntimeEventType;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.util.StringUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一调用同步或流式 ChatModel，并把流式增量转换为 Agent 实时事件。
 *
 * <p>该组件只负责一次模型请求，不修改 Run 阶段、消息历史或 Checkpoint。模型调用前后的生命周期、
 * 重试和预算处理仍由 AgentRunner 负责。</p>
 */
final class AgentModelInvoker {

    private final AgentRuntimeEventStream eventStream;

    AgentModelInvoker(AgentRuntimeEventStream eventStream) {
        this.eventStream = eventStream;
    }

    /** 根据 Invocation Context 的 streaming 配置选择模型调用方式。 */
    AiMessageResponse invoke(AgentRun run, Prompt prompt) {
        if (!run.getInvocationContext().isStreaming()) {
            return run.getAgent().getChatModel().chat(prompt, run.getAgent().getChatOptions());
        }
        return invokeStreaming(run, prompt);
    }

    /** 等待异步流关闭，并返回与同步接口一致的完整 AiMessageResponse。 */
    private AiMessageResponse invokeStreaming(AgentRun run, Prompt prompt) {
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<AiMessage> fullMessage = new AtomicReference<>();
        AtomicReference<ChatContext> chatContext = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        run.getAgent().getChatModel().chatStream(prompt, new StreamResponseListener() {
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                if (context != null) chatContext.set(context.getChatContext());
                AiMessage message = response == null ? null : response.getMessage();
                if (message == null) return;
                publishDeltas(run, message);
                if (message.isFinalDelta()) fullMessage.set(message);
            }

            @Override
            public void onError(StreamContext context, Throwable error) {
                failure.compareAndSet(null, error);
                // 流在打开前失败时模型实现可能不会再调用 onClose。
                closed.countDown();
            }

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
        }, run.getAgent().getChatOptions());

        awaitClose(run, closed);
        rethrowFailure(failure.get());
        ChatContext context = chatContext.get();
        if (context == null) {
            context = new ChatContext();
            context.setPrompt(prompt);
        }
        return new AiMessageResponse(context, null, fullMessage.get());
    }

    /** 最终完整帧不重复发布正文，只发布真正的增量内容。 */
    private void publishDeltas(AgentRun run, AiMessage message) {
        if (message.isFinalDelta()) return;
        if (StringUtil.hasText(message.getContent())) {
            eventStream.publish(run, AgentRuntimeEventType.MODEL_TEXT_DELTA,
                data("content", message.getContent()));
        }
        if (StringUtil.hasText(message.getReasoningContent())) {
            eventStream.publish(run, AgentRuntimeEventType.MODEL_REASONING_DELTA,
                data("content", message.getReasoningContent()));
        }
        if (message.hasToolCalls()) {
            eventStream.publish(run, AgentRuntimeEventType.MODEL_TOOL_CALL_DELTA,
                data("toolCalls", AgentMessageUtils.copyToolCalls(message.getToolCalls())));
        }
    }

    /** 有总时长预算时，流式等待不会超过当前 Run 的剩余时间。 */
    private void awaitClose(AgentRun run, CountDownLatch closed) {
        try {
            long maxDuration = run.getExecutionPolicy().getBudget().getMaxDurationMillis();
            if (maxDuration <= 0) {
                closed.await();
                return;
            }
            long remaining = maxDuration - (System.currentTimeMillis() - run.getCreatedAt());
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
