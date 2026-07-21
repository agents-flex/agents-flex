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
package com.agentsflex.core.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;

/**
 * 一次逻辑执行可以选择的 OpenTelemetry 运行时。
 *
 * <p>运行时封装同一套 {@link OpenTelemetry}、{@link Tracer} 和 {@link Meter}。Chat、Tool、HTTP
 * 埋点从当前上下文获取该对象，因此一次业务执行中的所有遥测数据会进入同一条路由。</p>
 *
 * <p>这是纯粹的可观测抽象，不对应也不依赖 Agents-Flex 中的任何 Agent 类型。宿主系统可以把它绑定到
 * 自己定义的智能体、租户、工作流或其他业务对象。</p>
 */
public interface ObservabilityRuntime {
    /**
     * 返回运行时的稳定标识，用于注册、诊断和区分不同遥测管道。
     */
    String getId();

    /**
     * 返回完整的 OpenTelemetry 门面，主要用于 Trace Context 的注入与提取。
     */
    OpenTelemetry getOpenTelemetry();

    /**
     * 返回 Agents-Flex 埋点创建 Span 时使用的 Tracer。
     */
    Tracer getTracer();

    /**
     * 返回 Agents-Flex 埋点创建 Counter、Histogram 等指标时使用的 Meter。
     */
    Meter getMeter();
}
