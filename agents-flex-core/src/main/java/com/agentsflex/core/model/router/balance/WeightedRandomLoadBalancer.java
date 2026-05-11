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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 加权随机负载均衡。
 * <p>
 * 用于：
 * <p>
 * 不同模型承担不同流量。
 * <p>
 * 例如：
 * <p>
 * GPT4o = 10
 * Qwen = 3
 * CheapModel = 1
 */
public class WeightedRandomLoadBalancer<T> implements ModelLoadBalancer<T> {

    @Override
    public ModelEndpoint<T> select(List<ModelEndpoint<T>> endpoints) {

        // 计算总权重
        int totalWeight = endpoints.stream()
            .mapToInt(ModelEndpoint::getWeight)
            .sum();

        // 随机一个数
        int random = ThreadLocalRandom.current().nextInt(totalWeight);

        int current = 0;

        // 根据权重区间选择 Endpoint
        for (ModelEndpoint<T> endpoint : endpoints) {
            current += endpoint.getWeight();
            if (random < current) {
                return endpoint;
            }
        }

        return endpoints.get(0);
    }
}
