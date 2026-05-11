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

import com.agentsflex.core.model.router.endpoint.ModelEndpoint;

/**
 * 熔断器。
 * <p>
 * 用于：
 * <p>
 * 防止故障模型拖垮整个系统。
 */
public interface CircuitBreaker<T> {

    /**
     * 是否允许请求。
     */
    boolean allowRequest(ModelEndpoint<T> endpoint);

    /**
     * 记录成功请求。
     */
    void recordSuccess(ModelEndpoint<T> endpoint);

    /**
     * 记录失败请求。
     */
    void recordFailure(ModelEndpoint<T> endpoint);

}
