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
package com.agentsflex.core.model.router.balance;

import com.agentsflex.core.model.router.endpoint.ModelEndpoint;

import java.util.Comparator;
import java.util.List;

/**
 * 最少活跃数负载均衡。
 * <p>
 * 生产环境推荐使用。
 * <p>
 * 适用于：
 * <p>
 * AI 模型场景。
 * <p>
 * 原因：
 * <p>
 * 不同模型：
 * <p>
 * - 响应时间不同
 * - 推理速度不同
 * - 输出长度不同
 * <p>
 * 因此：
 * <p>
 * RoundRobin 通常不是最佳选择。
 */
public class LeastActiveLoadBalancer<T> implements ModelLoadBalancer<T> {

    @Override
    public ModelEndpoint<T> select(List<ModelEndpoint<T>> endpoints) {
        return endpoints.stream()
            .min(
                Comparator.<ModelEndpoint<T>>comparingInt(
                        e -> e.getMetrics()
                            .activeRequests()
                    )
                    .thenComparingLong(
                        e -> e.getMetrics()
                            .avgLatencyMs()
                    )
            )
            .orElseThrow(
                () -> new IllegalStateException(
                    "No available endpoint."
                )
            );
    }
}
