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
package com.agentsflex.core.model.router.breaker;

import com.agentsflex.core.model.router.endpoint.EndpointStatus;
import com.agentsflex.core.model.router.endpoint.ModelEndpoint;

/**
 * 默认熔断器实现。
 */
public class DefaultCircuitBreaker<T> implements CircuitBreaker<T> {


    /**
     * 熔断阈值。
     * <p>
     * 连续失败多少次后熔断。
     */
    private final int failureThreshold;

    /**
     * 恢复时间。
     * <p>
     * 单位：毫秒。
     */
    private final long recoverMs;

    public DefaultCircuitBreaker() {
        this(5, 30000);
    }

    public DefaultCircuitBreaker(
        int failureThreshold,
        long recoverMs) {

        this.failureThreshold = failureThreshold;
        this.recoverMs = recoverMs;
    }

    @Override
    public boolean allowRequest(ModelEndpoint<T> endpoint) {

        // 正常状态直接允许
        if (endpoint.getStatus() == EndpointStatus.UP) {
            return true;
        }

        long now = System.currentTimeMillis();
        long diff = now - endpoint.getLastFailureTime().get();

        // 到达恢复时间
        if (diff >= recoverMs) {
            endpoint.setStatus(EndpointStatus.HALF_OPEN);
            return true;
        }

        return false;
    }

    @Override
    public void recordSuccess(ModelEndpoint<T> endpoint) {
        // 清空连续失败次数
        endpoint.getConsecutiveFailures().set(0);
        // 恢复正常状态
        endpoint.setStatus(EndpointStatus.UP);
    }

    @Override
    public void recordFailure(ModelEndpoint<T> endpoint) {
        int failures = endpoint.getConsecutiveFailures()
            .incrementAndGet();

        endpoint.getLastFailureTime().set(System.currentTimeMillis());

        // 达到熔断阈值
        if (failures >= failureThreshold) {
            endpoint.setStatus(EndpointStatus.DOWN);
        }
    }
}
