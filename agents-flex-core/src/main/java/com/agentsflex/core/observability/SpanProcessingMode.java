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

/**
 * Span 从 SDK 交给 Exporter 时采用的处理方式。
 */
public enum SpanProcessingMode {
    /**
     * 先进入内存队列，再按批次异步导出。适合生产环境，可降低业务线程上的导出开销。
     */
    BATCH,

    /**
     * Span 结束时立即调用 Exporter。主要用于本地调试、测试或无需缓冲的轻量后端。
     */
    SIMPLE
}
