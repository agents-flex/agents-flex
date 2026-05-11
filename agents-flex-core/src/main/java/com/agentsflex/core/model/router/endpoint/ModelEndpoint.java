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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型节点。
 * <p>
 * 用于表示：
 * <p>
 * 一个真实模型实例。
 * <p>
 * 例如：
 * <p>
 * - OpenAI GPT4o
 * - DeepSeek-R1
 * - Qwen-Max
 * - Gemini
 * - Claude
 * <p>
 * Endpoint 是 Router 的核心抽象。
 */
public class ModelEndpoint<T> {

    /**
     * 真实模型对象。
     */
    private final T model;

    /**
     * 运行时指标。
     */
    private final EndpointMetrics metrics = new EndpointMetrics();

    /**
     * 权重。
     * <p>
     * 用于：
     * <p>
     * WeightedRandomLoadBalancer。
     */
    private volatile int weight = 1;

    /**
     * 模型标签。
     * <p>
     * 用于：
     * <p>
     * 标签路由。
     * <p>
     * 例如：
     * <p>
     * - reasoning
     * - vision
     * - cheap
     * - fast
     */
    private final Set<String> tags = ConcurrentHashMap.newKeySet();


    /**
     * 当前节点状态。
     */
    private volatile EndpointStatus status = EndpointStatus.UP;

    /**
     * 连续失败次数
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /**
     * 最后失败时间
     */
    private final AtomicLong lastFailureTime = new AtomicLong();

    public ModelEndpoint(T model) {
        this.model = model;
    }

    public T getModel() {
        return model;
    }

    public EndpointMetrics getMetrics() {
        return metrics;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = Math.max(weight, 1);
    }

    public EndpointStatus getStatus() {
        return status;
    }

    public void setStatus(EndpointStatus status) {
        this.status = status;
    }

    public AtomicInteger getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public AtomicLong getLastFailureTime() {
        return lastFailureTime;
    }

    public void addTags(Set<String> tags) {
        this.tags.addAll(tags);
    }

    public boolean matchTags(Set<String> requiredTags) {

        if (requiredTags == null || requiredTags.isEmpty()) {
            return true;
        }

        return tags.containsAll(requiredTags);
    }
}
