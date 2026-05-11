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


/**
 * Endpoint 状态。
 * <p>
 * 一个模型节点在运行过程中，
 * 会存在以下三种状态。
 * <p>
 * UP：
 * 节点正常。
 * <p>
 * DOWN：
 * 节点不可用。
 * 通常是连续失败达到熔断阈值。
 * <p>
 * HALF_OPEN：
 * 半开状态。
 * 表示节点正在尝试恢复。
 * <p>
 * 熔断状态流转：
 * <p>
 * UP
 * ↓
 * 连续失败
 * ↓
 * DOWN
 * ↓
 * 恢复时间到达
 * ↓
 * HALF_OPEN
 * ↓
 * 成功
 * ↓
 * UP
 */
public enum EndpointStatus {

    /**
     * 节点正常。
     */
    UP,

    /**
     * 节点不可用。
     */
    DOWN,

    /**
     * 半开状态。
     */
    HALF_OPEN

}
