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
package com.agentsflex.observability.jdbc;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 OTel SDK 提交的一批完整 Span 写入 JDBC。
 *
 * <p>Exporter 本身不维护队列或重试状态，这些职责属于上游 SpanProcessor。数据库异常通过
 * {@link CompletableResultCode} 报告给 OTel，而不是向业务调用栈继续抛出。</p>
 */
final class JdbcSpanExporter implements SpanExporter {
    /** 记录数据库导出失败的日志记录器。 */
    private static final Logger logger = LoggerFactory.getLogger(JdbcSpanExporter.class);

    /** 负责实际 SQL 映射和事务提交的共享 JDBC Repository。 */
    private final JdbcTelemetryRepository repository;

    /** Exporter 是否已经 shutdown；使用原子变量兼容 OTel 调度线程与关闭线程并发。 */
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    JdbcSpanExporter(JdbcTelemetryRepository repository) {
        this.repository = repository;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        // shutdown 后拒绝新数据，防止 Route 生命周期结束后仍继续访问数据库。
        if (shutdown.get()) {
            return CompletableResultCode.ofFailure();
        }
        if (spans == null || spans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        try {
            // Repository 保证整个 batch 共用一个事务，任何一条失败都会回滚本批次。
            repository.writeSpans(spans);
            return CompletableResultCode.ofSuccess();
        } catch (Throwable error) {
            logger.warn("Failed to persist {} OpenTelemetry spans", spans.size(), error);
            return CompletableResultCode.ofExceptionalFailure(error);
        }
    }

    @Override
    public CompletableResultCode flush() {
        // Exporter 内部没有二级缓冲，数据在 export 返回前已提交，因此无需额外 flush。
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        // 这里只关闭 Exporter 的接收状态，不关闭由宿主应用拥有的 DataSource。
        shutdown.set(true);
        return CompletableResultCode.ofSuccess();
    }
}
