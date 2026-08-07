/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.core.model.router.chat;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.model.router.balance.LeastActiveLoadBalancer;
import com.agentsflex.core.model.router.balance.ModelLoadBalancer;
import com.agentsflex.core.model.router.breaker.CircuitBreaker;
import com.agentsflex.core.model.router.breaker.DefaultCircuitBreaker;
import com.agentsflex.core.model.router.core.AbstractModelRouter;
import com.agentsflex.core.model.router.core.RouterException;
import com.agentsflex.core.model.router.endpoint.ModelEndpoint;
import com.agentsflex.core.model.router.retry.DefaultRetryPolicy;
import com.agentsflex.core.model.router.retry.RetryPolicy;
import com.agentsflex.core.prompt.Prompt;

import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 面向 {@link ChatModel} 的进程内模型路由器。
 *
 * <p>它对调用方仍然表现为一个普通的 {@code ChatModel}，但会在多个候选模型节点之间完成
 * 标签过滤、负载均衡、失败重试、故障计量与熔断恢复。因此 Agent、ChatMemory 或业务代码无需
 * 感知当前实际调用的是哪个供应商或哪个部署节点。</p>
 *
 * <p>同步调用中，返回的错误响应会被转换为模型异常后进入重试流程；流式调用只允许在尚未把任何
 * 内容交给业务监听器前切换节点。已经输出文本、推理片段或 Tool Call 后继续切换会导致重复或拼接
 * 两个模型的结果，因此此时会将错误直接交给原监听器。</p>
 */
public class RoutedChatModel extends AbstractModelRouter<ChatModel> implements ChatModel {

    /**
     * 使用业务指定的节点、负载均衡、重试与熔断策略创建路由模型。
     *
     * <p>同一个 Router 中的节点应具备可替代的能力，例如工具协议、视觉能力、上下文长度与输出
     * 格式应满足同一业务契约。Router 只负责选择与切换，不会转换不同模型之间的能力差异。</p>
     */
    public RoutedChatModel(
        List<ModelEndpoint<ChatModel>> endpoints,
        ModelLoadBalancer<ChatModel> loadBalancer,
        RetryPolicy retryPolicy,
        CircuitBreaker<ChatModel> circuitBreaker) {

        super(
            endpoints,
            loadBalancer,
            retryPolicy,
            circuitBreaker
        );
    }

    /**
     * 使用默认策略创建路由模型：最少活跃请求负载均衡、最多三次重试以及默认熔断器。
     *
     * <p>三次重试不包含首次请求，单次调用在持续发生可重试故障时最多会尝试四次。</p>
     */
    public RoutedChatModel(List<ChatModel> models) {
        super(
            models.stream()
                .map(ModelEndpoint::new
                )
                .collect(Collectors.toList()),
            new LeastActiveLoadBalancer<>(),
            new DefaultRetryPolicy(3),
            new DefaultCircuitBreaker<>()
        );
    }

    @Override
    public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
        return execute(
            model -> {
                AiMessageResponse response = model.chat(prompt, options);
                // Provider 可能把 HTTP 失败封装为正常返回值；必须抛出后才能参与路由切换。
                if (response != null && response.isError()) {
                    response.throwIfError();
                }
                return response;
            },
            extractTags(options)
        );
    }

    @Override
    public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        // 一个流式故障转移周期内不重复尝试同一节点，全部尝试过后才开始下一轮重试。
        Set<ModelEndpoint<ChatModel>> attempted = new HashSet<>();
        streamAttempt(prompt, listener, options, attempted, new ArrayList<>(), 0, null);
    }

    /**
     * 启动一次流式请求，并在首个有效响应前处理可重试故障。
     *
     * <p>底层 {@code chatStream} 通常异步返回，不能复用同步 {@link #execute}：真正的连接失败
     * 会在 {@link StreamResponseListener#onError(StreamContext, Throwable)} 中发生。这里通过包装
     * Listener 把该异步边界纳入 Router 的指标、重试与熔断处理。</p>
     */
    private void streamAttempt(Prompt prompt, StreamResponseListener listener, ChatOptions options,
                               Set<ModelEndpoint<ChatModel>> attempted, List<Throwable> failures,
                               int retryCount,
                               Throwable previous) {
        List<ModelEndpoint<ChatModel>> allCandidates = filterEndpoints(extractTags(options));
        if (allCandidates.isEmpty()) {
            throw routerFailure("No available model endpoint.", previous, failures);
        }
        List<ModelEndpoint<ChatModel>> candidates = new ArrayList<>(allCandidates);
        candidates.removeIf(attempted::contains);
        if (candidates.isEmpty()) {
            // 只有全部当前候选都尝试过时才开始新一轮，避免状态过滤导致误重试。
            attempted.clear();
            candidates = allCandidates;
        }
        ModelEndpoint<ChatModel> endpoint = loadBalancer.select(candidates);
        attempted.add(endpoint);
        endpoint.getMetrics().beginRequest();
        long start = System.currentTimeMillis();
        // 回调可能来自网络线程，以下状态用于保证一次请求只结算一次。
        AtomicBoolean delivered = new AtomicBoolean(); // 是否已向业务侧交付任意模型内容
        AtomicBoolean opened = new AtomicBoolean();    // 是否已向业务侧发出 onOpen
        AtomicBoolean failed = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean switched = new AtomicBoolean();   // 当前失败是否已交由备用节点接管
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StreamResponseListener wrapped = new StreamResponseListener() {
            @Override
            public void onOpen(StreamContext context) {
                // 延迟转发。若连接刚打开就失败，业务侧只会看到最终选中节点的一次 onOpen。
                opened.set(true);
            }

            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                if (response != null && response.isError()) {
                    // 有些 Provider 会在流分片中携带错误响应，而不是直接触发 onError。
                    onError(context, response.toException());
                    return;
                }
                // 一旦内容已可见，不能再切换模型，否则会出现重复或混合输出。
                delivered.set(true);
                if (opened.compareAndSet(false, true)) listener.onOpen(context);
                listener.onMessage(context, response);
            }

            @Override
            public void onError(StreamContext context, Throwable error) {
                failed.set(true);
                failure.compareAndSet(null, error);
                if (!delivered.get() && retryPolicy.shouldRetry(retryCount, error)) {
                    // 尚未输出内容，可安全地由下一个候选节点重新开始完整响应。
                    switched.set(true);
                    finished.set(true);
                    endpoint.getMetrics().recordFailure(System.currentTimeMillis() - start);
                    if (shouldRecordEndpointFailure(error)) circuitBreaker.recordFailure(endpoint);
                    endpoint.getMetrics().endRequest();
                    failures.add(error);
                    if (!waitBeforeRetry(retryCount, error)) {
                        listener.onError(context, error);
                        return;
                    }
                    streamAttempt(prompt, listener, options, attempted, failures, retryCount + 1, error);
                    return;
                }
                // 已经输出过内容，或异常不可重试：保持原流的生命周期并交由调用方处理。
                if (opened.compareAndSet(false, true)) listener.onOpen(context);
                listener.onError(context, error);
            }

            @Override
            public void onClose(StreamContext context) {
                if (!finished.compareAndSet(false, true)) return;
                if (!switched.get()) {
                    listener.onClose(context);
                    if (failed.get()) {
                        endpoint.getMetrics().recordFailure(System.currentTimeMillis() - start);
                        if (shouldRecordEndpointFailure(failure.get())) {
                            circuitBreaker.recordFailure(endpoint);
                        }
                    } else {
                        endpoint.getMetrics().recordSuccess(System.currentTimeMillis() - start);
                        circuitBreaker.recordSuccess(endpoint);
                    }
                }
                endpoint.getMetrics().endRequest();
            }
        };
        try {
            endpoint.getModel().chatStream(prompt, wrapped, options);
        } catch (Exception e) {
            // 部分客户端会在创建连接时同步抛错；其切换语义与 onError 一致。
            if (!delivered.get() && retryPolicy.shouldRetry(retryCount, e)) {
                endpoint.getMetrics().recordFailure(System.currentTimeMillis() - start);
                if (shouldRecordEndpointFailure(e)) circuitBreaker.recordFailure(endpoint);
                endpoint.getMetrics().endRequest();
                failures.add(e);
                if (!waitBeforeRetry(retryCount, e)) {
                    throw routerFailure("Model request retry was interrupted.", e, failures);
                }
                streamAttempt(prompt, listener, options, attempted, failures, retryCount + 1, e);
            } else {
                endpoint.getMetrics().recordFailure(System.currentTimeMillis() - start);
                endpoint.getMetrics().endRequest();
                failures.add(e);
                throw routerFailure("All model requests failed.", e, failures);
            }
        }
    }

    private RouterException routerFailure(String message, Throwable cause, List<Throwable> failures) {
        RouterException result = new RouterException(message, cause);
        for (Throwable failure : failures) {
            if (failure != cause) result.addSuppressed(failure);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractTags(ChatOptions options) {

        if (options == null) {
            return Collections.emptySet();
        }

        Object value = options.getMetadata("modelTags");

        if (value instanceof Set<?>) {
            // 标签是调用方的路由约束，Endpoint 必须同时包含这些标签才可被选择。
            return (Set<String>) value;
        }

        return Collections.emptySet();
    }
}
