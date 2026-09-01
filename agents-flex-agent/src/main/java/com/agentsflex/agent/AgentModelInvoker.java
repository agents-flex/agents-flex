/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.core.util.StringUtil;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.FutureTask;

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
    private final java.util.concurrent.Executor modelExecutor;

    /**
     * 创建统一模型调用器。
     *
     * @param eventPublisher 用于发布模型流增量事件的发布器
     */
    AgentModelInvoker(AgentEventPublisher eventPublisher) {
        this(eventPublisher, Runnable::run);
    }

    AgentModelInvoker(AgentEventPublisher eventPublisher,
                      java.util.concurrent.Executor modelExecutor) {
        this.eventPublisher = eventPublisher;
        this.modelExecutor = modelExecutor;
    }

    /**
     * 根据当前 Turn 的 streaming 设置选择模型调用方式。
     */
    AiMessageResponse invoke(AgentTurn turn, Prompt prompt) {
        // Turn 级参数已经在 requestOptions 中复制；模型选择也基于裁剪后的最终 Prompt。
        ChatOptions options = requestOptions(turn);
        ChatModel model = selectModel(turn, prompt);
        if (!turn.isStreaming()) {
            long timeout = turn.getExecutionPolicy().getModelCallTimeoutMillis();
            if (timeout <= 0) return model.chat(prompt, options);
            FutureTask<AiMessageResponse> task = new FutureTask<>(() -> model.chat(prompt, options));
            modelExecutor.execute(task);
            try {
                return task.get(timeout, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException error) {
                task.cancel(true);
                throw new IllegalStateException("model call exceeded timeout: " + timeout + "ms", error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("model call was interrupted", error);
            } catch (java.util.concurrent.ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new IllegalStateException("model call failed", cause);
            }
        }
        return invokeStreaming(turn, prompt, options, model);
    }

    /**
     * 等待异步流关闭，并返回与同步接口一致的完整 AiMessageResponse。
     */
    private AiMessageResponse invokeStreaming(AgentTurn turn, Prompt prompt, ChatOptions options, ChatModel model) {
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<AiMessage> fullMessage = new AtomicReference<>();
        AtomicReference<ChatContext> chatContext = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> textDeltaPublished = new AtomicReference<>(false);

        StreamResponseListener listener = new StreamResponseListener() {
            /**
             * 保存底层模型客户端创建的 ChatContext，供流关闭后组装完整响应。
             * @param context 当前流上下文
             */
            @Override
            public void onOpen(StreamContext context) {
                chatContext.set(context.getChatContext());
            }

            /**
             * 接收模型产生的单个流式帧。
             *
             * <p>普通增量帧会立即发布为运行时事件；最终帧只用于保留完整消息。
             * 如果服务端没有发送任何正文增量，则把最终聚合正文发布一次作为兜底，避免
             * 兼容接口虽然声明 stream=true 但只返回完整正文时丢失用户可见输出。</p>
             */
            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                AiMessage message = response == null ? null : response.getMessage();
                if (message == null) return;

                if (message.isFinalDelta()) {
                    fullMessage.set(message);
                    publishFinalTextIfNeeded(turn, message, textDeltaPublished);
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
                    if (context.getFullMessage() != null) {
                        fullMessage.set(context.getFullMessage());
                        // 一些兼容服务只在流关闭时提供聚合正文；此处仍早于 MODEL_COMPLETED 事件。
                        publishFinalTextIfNeeded(turn, context.getFullMessage(), textDeltaPublished);
                    }
                    if (context.getThrowable() != null) {
                        failure.compareAndSet(null, context.getThrowable());
                    }
                }
                closed.countDown();
            }
        };
        // chatStream 有两种常见实现：立即返回并异步回调，或阻塞到网络流结束。
        // 统一放入模型执行器，才能让入口阻塞也受 modelCallTimeoutMillis 保护。
        FutureTask<Void> streamTask = new FutureTask<>(() -> {
            try {
                model.chatStream(prompt, listener, options);
            } catch (RuntimeException error) {
                failure.compareAndSet(null, error);
                closed.countDown();
                throw error;
            } catch (Error error) {
                failure.compareAndSet(null, error);
                closed.countDown();
                throw error;
            }
            return null;
        });
        modelExecutor.execute(streamTask);

        awaitClose(turn, closed, streamTask);
        rethrowFailure(failure.get());
        // 包装器或兼容客户端可能只在关闭完成后补齐聚合消息；返回 Runner 前做最后一次兜底。
        publishFinalTextIfNeeded(turn, fullMessage.get(), textDeltaPublished);
        ChatContext context = chatContext.get();
        if (context == null) {
            context = new ChatContext();
            context.setPrompt(prompt);
        }
        return new AiMessageResponse(context, null, fullMessage.get());
    }

    /**
     * 多模态模型的选择以实际发送给模型的 Prompt 为准，而非仅检查最新用户消息。
     * 这样历史图片仍处于上下文窗口时，后续文本追问也不会错误地落到纯文本模型。
     */
    private ChatModel selectModel(AgentTurn turn, Prompt prompt) {
        if (turn.getAgent().getModelSelector() != null) {
            ChatModel selected = turn.getAgent().getModelSelector().select(turn, prompt,
                turn.getAgent().getChatModel(), turn.getAgent().getMultimodalChatModel());
            if (selected == null) throw new IllegalStateException("AgentModelSelector returned null");
            return selected;
        }
        ChatModel multimodal = turn.getAgent().getMultimodalChatModel();
        if (multimodal == null || !(prompt instanceof MemoryPrompt)) return turn.getAgent().getChatModel();
        for (Message message : ((MemoryPrompt) prompt).getMessages()) {
            if (message instanceof UserMessage && hasMultimodalContent((UserMessage) message)) return multimodal;
        }
        return turn.getAgent().getChatModel();
    }

    /**
     * 判断用户消息是否引用了任意图片、音频、视频或文件资源。
     *
     * @param message 待检查用户消息
     * @return 任一媒体列表非空时返回 {@code true}
     */
    private boolean hasMultimodalContent(UserMessage message) {
        return hasContent(message.getImageUrls()) || hasContent(message.getAudioUrls())
            || hasContent(message.getVideoUrls()) || hasContent(message.getFileUrls());
    }

    /**
     * @return 给定媒体列表是否至少包含一个元素
     */
    private boolean hasContent(List<String> values) {
        return values != null && !values.isEmpty();
    }

    /**
     * 从 Agent 的静态模型配置创建本次请求副本，并绑定当前 Turn 的关联标识。
     */
    private ChatOptions requestOptions(AgentTurn turn) {
        ChatOptions configured = turn.getChatOptionsOverride();
        if (configured == null) configured = turn.getAgent().getChatOptions();
        ChatOptions options = configured == null ? new ChatOptions() : configured.copy();
        if (StringUtil.hasText(turn.getConversationId())) {
            options.setContextConversationId(turn.getConversationId());
        }
        options.setContextTurnId(turn.getId());
        return options;
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
     * 兼容只返回聚合正文的流式服务，确保最终文本仍在 MODEL_COMPLETED 前作为事件发布。
     */
    private void publishFinalTextIfNeeded(AgentTurn turn, AiMessage message,
                                          AtomicReference<Boolean> textDeltaPublished) {
        if (Boolean.TRUE.equals(textDeltaPublished.get()) || message == null) return;
        String content = StringUtil.hasText(message.getFullContent())
            ? message.getFullContent() : message.getContent();
        if (!StringUtil.hasText(content)) {
            content = message.getTextContent();
        }
        if (StringUtil.hasText(content)) {
            eventPublisher.publish(turn, AgentEventType.MODEL_TEXT_DELTA,
                data("content", content));
            textDeltaPublished.set(true);
        }
    }

    /**
     * 有总时长预算时，流式等待不会超过当前 Turn 的剩余时间。
     */
    private void awaitClose(AgentTurn turn, CountDownLatch closed, FutureTask<Void> streamTask) {
        try {
            long maxDuration = turn.getExecutionPolicy().getBudget().getMaxDurationMillis();
            long callTimeout = turn.getExecutionPolicy().getModelCallTimeoutMillis();
            if (maxDuration <= 0 && callTimeout <= 0) {
                closed.await();
                // 若模型实现只在入口抛错而没有触发回调，读取 FutureTask 的异常。
                observeStreamTask(streamTask);
                return;
            }
            long remaining = maxDuration <= 0 ? Long.MAX_VALUE
                : maxDuration - (System.currentTimeMillis() - turn.getCreatedAt());
            if (callTimeout > 0) remaining = Math.min(remaining, callTimeout);
            if (remaining <= 0) {
                streamTask.cancel(true);
                throw new IllegalStateException("streaming model call exceeded maxDurationMillis");
            }
            long deadline = System.currentTimeMillis() + remaining;
            while (closed.getCount() > 0) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    streamTask.cancel(true);
                    String limit = callTimeout > 0 && (maxDuration <= 0 || callTimeout <= remaining)
                        ? "model call timeout" : "maxDurationMillis";
                    throw new IllegalStateException("streaming model call exceeded " + limit);
                }
                closed.await(Math.min(left, 20), TimeUnit.MILLISECONDS);
            }
            observeStreamTask(streamTask);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("streaming model call was interrupted", error);
        }
    }

    /**
     * 读取流入口任务的完成状态；异常已经同步写入 failure，由调用方统一转换。
     */
    private void observeStreamTask(FutureTask<Void> streamTask) throws InterruptedException {
        if (!streamTask.isDone()) return;
        try {
            streamTask.get();
        } catch (java.util.concurrent.ExecutionException ignored) {
            // invokeStreaming 随后会通过 rethrowFailure 保留原始异常类型。
        }
    }

    /**
     * 保留运行时异常类型重新抛出，并把其他流失败统一包装为状态异常。
     *
     * @param error 流监听器记录的失败；为空时不做处理
     */
    private void rethrowFailure(Throwable error) {
        if (error == null) return;
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        throw new IllegalStateException("streaming model call failed", error);
    }

    /**
     * 创建包含单个键值的有序事件数据 Map。
     *
     * @param key   事件字段名
     * @param value 事件字段值
     * @return 可修改 Map
     */
    private Map<String, Object> data(String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(key, value);
        return data;
    }
}
