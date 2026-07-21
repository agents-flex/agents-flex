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
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.DefaultAggregationSelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 OTel SDK 周期性收集的 MetricData 拆成 point 并写入 JDBC。
 *
 * <p>该 Exporter 请求 cumulative temporality，数据库中的相邻记录表示同一时间序列在不同时间点的累计值。
 * 查询侧需要按 metric name、attributes 和时间排序后计算所需变化量。</p>
 */
final class JdbcMetricExporter implements MetricExporter {
    /** 记录数据库导出失败的日志记录器。 */
    private static final Logger logger = LoggerFactory.getLogger(JdbcMetricExporter.class);

    // JDBC 表保留每次采集的完整累计状态，选择 cumulative 可避免导出失败时永久丢失某个增量区间。
    private static final AggregationTemporalitySelector TEMPORALITY =
        AggregationTemporalitySelector.alwaysCumulative();

    /** 各类 OTel instrument 使用的默认聚合策略，保持与 SDK 默认行为一致。 */
    private static final DefaultAggregationSelector AGGREGATION = DefaultAggregationSelector.getDefault();

    /** 负责 point 展开、SQL 映射和事务提交的共享 JDBC Repository。 */
    private final JdbcTelemetryRepository repository;

    /** Exporter 是否已经 shutdown；使用原子变量兼容 Metric Reader 调度线程与关闭线程并发。 */
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    JdbcMetricExporter(JdbcTelemetryRepository repository) {
        this.repository = repository;
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
        if (shutdown.get()) {
            return CompletableResultCode.ofFailure();
        }
        if (metrics == null || metrics.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        try {
            // 一个 MetricData 可能包含多组 attributes point，Repository 会展开后在同一事务批量写入。
            repository.writeMetrics(metrics);
            return CompletableResultCode.ofSuccess();
        } catch (Throwable error) {
            logger.warn("Failed to persist {} OpenTelemetry metrics", metrics.size(), error);
            return CompletableResultCode.ofExceptionalFailure(error);
        }
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
        return TEMPORALITY.getAggregationTemporality(instrumentType);
    }

    @Override
    public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
        return AGGREGATION.getDefaultAggregation(instrumentType);
    }

    @Override
    public CompletableResultCode flush() {
        // Exporter 没有自身缓冲；PeriodicMetricReader 的 forceFlush 会主动触发一次 collect + export。
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        // DataSource 不属于 Exporter，关闭时只拒绝后续 export。
        shutdown.set(true);
        return CompletableResultCode.ofSuccess();
    }
}
