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

final class MetricPointRecord {
    final MetricData metric;
    final PointData point;
    final String temporality;
    final Boolean monotonic;
    Long valueLong;
    Double valueDouble;
    Long count;
    Double sum;
    Double min;
    Double max;
    String dataJson;

    private MetricPointRecord(MetricData metric, PointData point, String temporality, Boolean monotonic) {
        this.metric = metric;
        this.point = point;
        this.temporality = temporality;
        this.monotonic = monotonic;
        populateValue(point);
    }

    static List<MetricPointRecord> from(Collection<MetricData> metrics) {
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
