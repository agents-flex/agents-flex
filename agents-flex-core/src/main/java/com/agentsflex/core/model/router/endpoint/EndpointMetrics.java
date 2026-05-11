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
package com.agentsflex.core.model.router.endpoint;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Endpoint 运行时指标。
 * <p>
 * 该类主要用于：
 * <p>
 * 1、负载均衡
 * 2、熔断判断
 * 3、运行时调度
 * 4、实时状态统计
 * <p>
 * 注意：
 * <p>
 * 这里的 Metrics 属于：
 * <p>
 * “运行时内存态指标”
 * <p>
 * 主要服务于：
 * <p>
 * - LeastActiveLoadBalancer
 * - CircuitBreaker
 * - Retry
 * - Router Runtime
 * <p>
 * 而不是：
 * <p>
 * OpenTelemetry / Prometheus 这种观测系统。
 * <p>
 * 因此：
 * <p>
 * 这里的数据要求：
 * <p>
 * - 极低开销
 * - JVM 内存态
 * - 高并发安全
 * - 实时可读
 * <p>
 * 所以这里全部使用：
 * <p>
 * - AtomicInteger
 * - AtomicLong
 * <p>
 * 来实现无锁并发统计。
 *
 * @author michael
 */
public class EndpointMetrics {

    /**
     * 当前活跃请求数（正在执行中的请求）
     * <p>
     * 用于：
     * <p>
     * LeastActiveLoadBalancer
     * <p>
     * 例如：
     * <p>
     * 当前哪个模型最空闲。
     */
    private final AtomicInteger activeRequests = new AtomicInteger();

    /**
     * 总请求数
     * <p>
     * 包括：
     * <p>
     * - 成功
     * - 失败
     */
    private final AtomicLong totalRequests = new AtomicLong();

    /**
     * 成功请求数
     */
    private final AtomicLong successRequests = new AtomicLong();

    /**
     * 失败请求数
     */
    private final AtomicLong failedRequests = new AtomicLong();

    /**
     * 总延迟（毫秒）
     * <p>
     * 用于计算：
     * <p>
     * 平均延迟。
     */
    private final AtomicLong totalLatencyMs = new AtomicLong();

    /**
     * 请求开始。
     * <p>
     * 调用时机：
     * <p>
     * Router 在真正调用模型前执行。
     */
    public void beginRequest() {

        // 当前活跃请求 +1
        activeRequests.incrementAndGet();

        // 总请求数 +1
        totalRequests.incrementAndGet();
    }

    /**
     * 请求结束。
     * <p>
     * 注意：
     * <p>
     * 必须放到 finally 中调用，
     * 避免并发计数错误。
     */
    public void endRequest() {

        // 当前活跃请求 -1
        activeRequests.decrementAndGet();
    }

    /**
     * 记录成功请求。
     *
     * @param latencyMs 请求耗时（毫秒）
     */
    public void recordSuccess(long latencyMs) {

        // 成功请求 +1
        successRequests.incrementAndGet();

        // 累加总延迟
        totalLatencyMs.addAndGet(latencyMs);
    }

    /**
     * 记录失败请求。
     *
     * @param latencyMs 请求耗时（毫秒）
     */
    public void recordFailure(long latencyMs) {

        // 失败请求 +1
        failedRequests.incrementAndGet();

        // 累加总延迟
        totalLatencyMs.addAndGet(latencyMs);
    }

    /**
     * 当前活跃请求数。
     */
    public int activeRequests() {
        return activeRequests.get();
    }

    /**
     * 总请求数。
     */
    public long totalRequests() {
        return totalRequests.get();
    }

    /**
     * 成功请求数。
     */
    public long successRequests() {
        return successRequests.get();
    }

    /**
     * 失败请求数。
     */
    public long failedRequests() {
        return failedRequests.get();
    }

    /**
     * 成功率。
     * <p>
     * 返回值：
     * <p>
     * 0 ~ 1
     * <p>
     * 例如：
     * <p>
     * 0.98 = 98%
     */
    public double successRate() {

        long total = totalRequests.get();

        // 没有请求时默认成功率为 100%
        if (total == 0) {
            return 1D;
        }

        return (double) successRequests.get() / total;
    }

    /**
     * 平均延迟（毫秒）
     */
    public long avgLatencyMs() {

        long total = totalRequests.get();

        if (total == 0) {
            return 0;
        }

        return totalLatencyMs.get() / total;
    }

    @Override
    public String toString() {

        return "EndpointMetrics{" +
            "activeRequests=" + activeRequests() +
            ", totalRequests=" + totalRequests() +
            ", successRequests=" + successRequests() +
            ", failedRequests=" + failedRequests() +
            ", successRate=" + successRate() +
            ", avgLatencyMs=" + avgLatencyMs() +
            '}';
    }
}
