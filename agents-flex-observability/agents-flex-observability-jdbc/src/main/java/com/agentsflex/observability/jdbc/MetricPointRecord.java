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

import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramBuckets;
import io.opentelemetry.sdk.metrics.data.ExponentialHistogramPointData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.metrics.data.SummaryPointData;
import io.opentelemetry.sdk.metrics.data.ValueAtQuantile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 OTel 多态 Metric point 归一化为一行 JDBC 记录。
 *
 * <p>Long/Double Gauge 或 Sum 直接落入标量列；Histogram、ExponentialHistogram 和 Summary 的常用统计
 * 落入 count/sum/min/max，桶或分位数等结构化数据放入 JSON。</p>
 */
final class MetricPointRecord {
    /** point 所属的原始 MetricData，提供名称、类型、Resource 和 instrumentation scope。 */
    final MetricData metric;

    /** 当前数据库行对应的具体 point，提供时间戳和 attributes。 */
    final PointData point;

    /** 聚合时间性名称，例如 CUMULATIVE；不适用的 Gauge/Summary 为 null。 */
    final String temporality;

    /** Sum 是否单调递增；非 Sum 类型为 null。 */
    final Boolean monotonic;

    /** Long point 的标量值；其他 point 类型为 null。 */
    Long valueLong;

    /** Double point 的标量值；其他 point 类型为 null。 */
    Double valueDouble;

    /** Histogram、ExponentialHistogram 或 Summary 的样本数量。 */
    Long count;

    /** Histogram、ExponentialHistogram 或 Summary 的样本总和。 */
    Double sum;

    /** Histogram 的最小值；未启用 min/max 统计或类型不适用时为 null。 */
    Double min;

    /** Histogram 的最大值；未启用 min/max 统计或类型不适用时为 null。 */
    Double max;

    /** 桶边界、桶计数或 Summary 分位数等无法固定列化的聚合详情 JSON。 */
    String dataJson;

    private MetricPointRecord(MetricData metric, PointData point, String temporality, Boolean monotonic) {
        this.metric = metric;
        this.point = point;
        this.temporality = temporality;
        this.monotonic = monotonic;
        populateValue(point);
    }

    static List<MetricPointRecord> from(Collection<MetricData> metrics) {
        // 一个 MetricData 可以包含多组不同 attributes 的 point，每个 point 对应数据库中的一行。
        List<MetricPointRecord> records = new ArrayList<>();
        for (MetricData metric : metrics) {
            String temporality = temporality(metric);
            Boolean monotonic = monotonic(metric);
            for (PointData point : metric.getData().getPoints()) {
                records.add(new MetricPointRecord(metric, point, temporality, monotonic));
            }
        }
        return records;
    }

    private static String temporality(MetricData metric) {
        MetricDataType type = metric.getType();
        if (type == MetricDataType.LONG_SUM) {
            return metric.getLongSumData().getAggregationTemporality().name();
        }
        if (type == MetricDataType.DOUBLE_SUM) {
            return metric.getDoubleSumData().getAggregationTemporality().name();
        }
        if (type == MetricDataType.HISTOGRAM) {
            return metric.getHistogramData().getAggregationTemporality().name();
        }
        if (type == MetricDataType.EXPONENTIAL_HISTOGRAM) {
            return metric.getExponentialHistogramData().getAggregationTemporality().name();
        }
        return null;
    }

    private static Boolean monotonic(MetricData metric) {
        if (metric.getType() == MetricDataType.LONG_SUM) {
            return metric.getLongSumData().isMonotonic();
        }
        if (metric.getType() == MetricDataType.DOUBLE_SUM) {
            return metric.getDoubleSumData().isMonotonic();
        }
        return null;
    }

    private void populateValue(PointData point) {
        // 按 OTel point 的具体类型提取稳定列；未知的新类型仍保留基础元数据，不中断整批导出。
        if (point instanceof LongPointData) {
            valueLong = ((LongPointData) point).getValue();
            return;
        }
        if (point instanceof DoublePointData) {
            valueDouble = ((DoublePointData) point).getValue();
            return;
        }
        if (point instanceof HistogramPointData) {
            HistogramPointData histogram = (HistogramPointData) point;
            count = histogram.getCount();
            sum = histogram.getSum();
            min = histogram.hasMin() ? histogram.getMin() : null;
            max = histogram.hasMax() ? histogram.getMax() : null;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("boundaries", histogram.getBoundaries());
            data.put("counts", histogram.getCounts());
            dataJson = TelemetryJson.value(data);
            return;
        }
        if (point instanceof ExponentialHistogramPointData) {
            ExponentialHistogramPointData histogram = (ExponentialHistogramPointData) point;
            count = histogram.getCount();
            sum = histogram.getSum();
            min = histogram.hasMin() ? histogram.getMin() : null;
            max = histogram.hasMax() ? histogram.getMax() : null;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("scale", histogram.getScale());
            data.put("zeroCount", histogram.getZeroCount());
            data.put("positive", buckets(histogram.getPositiveBuckets()));
            data.put("negative", buckets(histogram.getNegativeBuckets()));
            dataJson = TelemetryJson.value(data);
            return;
        }
        if (point instanceof SummaryPointData) {
            SummaryPointData summary = (SummaryPointData) point;
            count = summary.getCount();
            sum = summary.getSum();
            List<Map<String, Object>> quantiles = new ArrayList<>();
            for (ValueAtQuantile value : summary.getValues()) {
                Map<String, Object> quantile = new LinkedHashMap<>();
                quantile.put("quantile", value.getQuantile());
                quantile.put("value", value.getValue());
                quantiles.add(quantile);
            }
            dataJson = TelemetryJson.value(quantiles);
        }
    }

    private static Map<String, Object> buckets(ExponentialHistogramBuckets buckets) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("offset", buckets.getOffset());
        value.put("counts", buckets.getBucketCounts());
        return value;
    }
}
