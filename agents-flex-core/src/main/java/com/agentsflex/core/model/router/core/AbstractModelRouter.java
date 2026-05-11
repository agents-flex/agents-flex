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

import com.agentsflex.core.model.router.balance.ModelLoadBalancer;
import com.agentsflex.core.model.router.breaker.CircuitBreaker;
import com.agentsflex.core.model.router.endpoint.EndpointStatus;
import com.agentsflex.core.model.router.endpoint.ModelEndpoint;
import com.agentsflex.core.model.router.retry.RetryPolicy;

import java.util.List;
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

        this.endpoints = endpoints;
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

        int retry = 0;

        while (true) {

            // 过滤可用 Endpoint
            List<ModelEndpoint<T>> candidates = filterEndpoints(tags);

            if (candidates.isEmpty()) {
                throw new RouterException("No available model endpoint.");
            }

            // 负载均衡选择节点
            ModelEndpoint<T> endpoint = loadBalancer.select(candidates);

            long start = System.currentTimeMillis();

            // 请求开始
            endpoint.getMetrics().beginRequest();

            try {
                // 执行模型调用
                R result = invoker.invoke(endpoint.getModel());
                long latency = System.currentTimeMillis() - start;

                // 记录成功指标
                endpoint.getMetrics().recordSuccess(latency);

                // 熔断恢复
                circuitBreaker.recordSuccess(endpoint);
                return result;
            } catch (Throwable e) {
                long latency = System.currentTimeMillis() - start;

                // 记录失败指标
                endpoint.getMetrics().recordFailure(latency);

                // 熔断失败记录
                circuitBreaker.recordFailure(endpoint);

                lastThrowable = e;

                // 判断是否继续重试
                if (!retryPolicy.shouldRetry(retry++, e)) {
                    break;
                }
            } finally {
                // 请求结束
                endpoint.getMetrics().endRequest();
            }
        }

        throw new RouterException("All model requests failed.", lastThrowable);
    }


    /**
     * 过滤可用 Endpoint。
     */
    private List<ModelEndpoint<T>> filterEndpoints(Set<String> tags) {

        return endpoints.stream()
            // 过滤 DOWN 节点
            .filter(endpoint ->
                endpoint.getStatus() != EndpointStatus.DOWN
            )
            // 熔断判断
            .filter(circuitBreaker::allowRequest)
            // 标签路由
            .filter(endpoint ->
                endpoint.matchTags(tags)
            )
            .collect(Collectors.toList());
    }
}
