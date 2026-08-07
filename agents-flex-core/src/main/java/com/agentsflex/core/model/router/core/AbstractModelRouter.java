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
package com.agentsflex.core.model.router.core;

import com.agentsflex.core.model.exception.ModelException;
import com.agentsflex.core.model.exception.ModelOverloadedException;
import com.agentsflex.core.model.exception.ModelQuotaExceededException;
import com.agentsflex.core.model.exception.ModelRateLimitException;
import com.agentsflex.core.model.exception.TokenLimitExceededException;
import com.agentsflex.core.model.router.balance.ModelLoadBalancer;
import com.agentsflex.core.model.router.breaker.CircuitBreaker;
import com.agentsflex.core.model.router.endpoint.EndpointStatus;
import com.agentsflex.core.model.router.endpoint.ModelEndpoint;
import com.agentsflex.core.model.router.retry.RetryPolicy;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Router 抽象基类。
 * <p>
 * 整个系统核心。
 * <p>
 * 所有模型类型：
 * <p>
 * - ChatModel
 * - EmbeddingModel
 * - ImageModel
 * <p>
 * 最终都会走这里。
 */
public abstract class AbstractModelRouter<T> {

    /**
     * 所有 Endpoint。
     */
    protected final List<ModelEndpoint<T>> endpoints;

    /**
     * 负载均衡器。
     */
    protected final ModelLoadBalancer<T> loadBalancer;

    /**
     * 重试策略。
     */
    protected final RetryPolicy retryPolicy;

    /**
     * 熔断器。
     */
    protected final CircuitBreaker<T> circuitBreaker;

    protected AbstractModelRouter(
        List<ModelEndpoint<T>> endpoints,
        ModelLoadBalancer<T> loadBalancer,
        RetryPolicy retryPolicy,
        CircuitBreaker<T> circuitBreaker) {

        Objects.requireNonNull(endpoints, "endpoints must not be null");
        Objects.requireNonNull(loadBalancer, "loadBalancer must not be null");
        Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        Objects.requireNonNull(circuitBreaker, "circuitBreaker must not be null");
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints must not be empty");
        }
        for (ModelEndpoint<T> endpoint : endpoints) {
            Objects.requireNonNull(endpoint, "endpoint must not be null");
        }
        // Router 生命周期内节点集合应稳定，运行时健康状态由 Endpoint 自身维护。
        this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
        this.loadBalancer = loadBalancer;
        this.retryPolicy = retryPolicy;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * 执行模型请求。
     * <p>
     * 整个 Router 核心入口。
     */
    protected <R> R execute(ModelInvoker<T, R> invoker, Set<String> tags) {

        Throwable lastThrowable = null;
        List<Throwable> failures = new ArrayList<>();
        Set<ModelEndpoint<T>> attempted = new HashSet<>();

        int retry = 0;

        while (true) {

            // 过滤可用 Endpoint
            List<ModelEndpoint<T>> allCandidates = filterEndpoints(tags);
            if (allCandidates.isEmpty()) {
                throw new RouterException("No available model endpoint.");
            }
            List<ModelEndpoint<T>> candidates = new ArrayList<>(allCandidates);
            candidates.removeIf(attempted::contains);
            if (candidates.isEmpty()) {
                // 只有全部当前候选都已尝试过，才开启下一轮；不能因状态过滤导致误重置。
                attempted.clear();
                candidates = allCandidates;
            }

            // 负载均衡选择节点
            ModelEndpoint<T> endpoint = loadBalancer.select(candidates);
            attempted.add(endpoint);

            long start = System.currentTimeMillis();

            // 请求开始
            endpoint.getMetrics().beginRequest();

            try {
                // 执行模型调用
                R result = invoker.invoke(endpoint.getModel());
                if (result == null) {
                    throw new RouterException("Model endpoint returned null result.");
                }
                long latency = System.currentTimeMillis() - start;

                // 记录成功指标
                endpoint.getMetrics().recordSuccess(latency);

                // 熔断恢复
                circuitBreaker.recordSuccess(endpoint);
                return result;
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;

                // 记录失败指标
                endpoint.getMetrics().recordFailure(latency);

                // 熔断失败记录
                if (shouldRecordEndpointFailure(e)) {
                    circuitBreaker.recordFailure(endpoint);
                }

                lastThrowable = e;
                failures.add(e);

                // 判断是否继续重试
                if (!retryPolicy.shouldRetry(retry, e) || !waitBeforeRetry(retry, e)) {
                    break;
                }
                retry++;
            } finally {
                // 请求结束
                endpoint.getMetrics().endRequest();
            }
        }

        RouterException result = new RouterException("All model requests failed.", lastThrowable);
        for (Throwable failure : failures) {
            if (failure != lastThrowable) result.addSuppressed(failure);
        }
        throw result;
    }

    /**
     * 请求本身确定有问题时不熔断节点，避免上下文超限或额度耗尽污染健康状态。
     */
    protected boolean shouldRecordEndpointFailure(Throwable throwable) {
        throwable = unwrap(throwable);
        if (throwable instanceof ModelRateLimitException
            || throwable instanceof ModelOverloadedException) {
            return true;
        }
        return !(throwable instanceof TokenLimitExceededException
            || throwable instanceof ModelQuotaExceededException
            || throwable instanceof ModelException);
    }

    private Throwable unwrap(Throwable throwable) {
        while (throwable != null
            && (throwable instanceof java.util.concurrent.CompletionException
            || throwable instanceof java.util.concurrent.ExecutionException)
            && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }
        return throwable;
    }

    /**
     * 在 Router 层执行策略给出的退避时间。中断时保留中断标记并结束重试，避免吞掉取消信号。
     */
    protected boolean waitBeforeRetry(int retryCount, Throwable throwable) {
        long delay = Math.max(0L, retryPolicy.retryDelayMillis(retryCount, throwable));
        if (delay == 0L) return true;
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }


    /**
     * 过滤可用 Endpoint。
     */
    protected List<ModelEndpoint<T>> filterEndpoints(Set<String> tags) {

        return endpoints.stream()
            // 相同 endpointId 的重复配置代表同一个逻辑节点，不能在同一轮被重复尝试。
            .distinct()
            // 先交给熔断器判断，DOWN 节点才有机会在恢复时间到达后进入 HALF_OPEN。
            .filter(circuitBreaker::allowRequest)
            .filter(endpoint -> endpoint.getStatus() != EndpointStatus.DOWN)
            // 标签路由
            .filter(endpoint ->
                endpoint.matchTags(tags)
            )
            .collect(Collectors.toList());
    }
}
