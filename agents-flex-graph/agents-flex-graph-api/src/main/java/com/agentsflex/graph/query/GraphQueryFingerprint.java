package com.agentsflex.graph.query;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 为统一遍历查询生成稳定的结构指纹。
 *
 * <p>默认分页游标使用该指纹绑定产生游标的查询，避免把查询 A 的 offset 游标误用于查询 B。
 * 指纹只描述查询语义，不包含 skip 和 limit，因为这两个字段会随分页过程变化。</p>
 */
final class GraphQueryFingerprint {
    /**
     * 工具类不允许实例化。
     */
    private GraphQueryFingerprint() {
    }

    /**
     * 计算遍历查询的 SHA-256 指纹，并截取前 16 个十六进制字符控制游标长度。
     *
     * @param query 已通过构造器校验的遍历查询
     * @return 可稳定复现的查询结构指纹
     */
    static String of(TraversalQuery query) {
        if (query == null) throw new IllegalArgumentException("query must not be null");
        StringBuilder canonical = new StringBuilder();
        appendNode(canonical, query.getStart());
        for (TraversalQuery.Step step : query.getSteps()) {
            TraversalQuery.EdgePattern edge = step.getEdge();
            append(canonical, edge.getAlias());
            append(canonical, edge.getType());
            append(canonical, edge.getDirection().name());
            append(canonical, edge.getMinHops());
            append(canonical, edge.getMaxHops());
            appendNode(canonical, step.getNode());
        }
        appendFilter(canonical, query.getFilter());
        for (TraversalQuery.Projection projection : query.getProjections()) {
            append(canonical, projection.getKind().name());
            append(canonical, projection.getAlias());
            append(canonical, projection.getProperty());
            append(canonical, projection.getOutputName());
            append(canonical, projection.getAggregateFunction() == null
                ? null : projection.getAggregateFunction().name());
        }
        for (TraversalQuery.Sort sort : query.getSorts()) {
            append(canonical, sort.getAlias());
            append(canonical, sort.getProperty());
            append(canonical, sort.getDirection().name());
        }
        append(canonical, query.isDistinct());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", bytes[i] & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 将节点别名和可选标签写入规范串。
     */
    private static void appendNode(StringBuilder target, TraversalQuery.NodePattern node) {
        append(target, node.getAlias());
        append(target, node.getLabel());
    }

    /**
     * 递归写入过滤树，保留节点类型、操作符和比较值的数据类型。
     */
    private static void appendFilter(StringBuilder target, GraphFilter filter) {
        if (filter == null) {
            append(target, null);
            return;
        }
        append(target, filter.getKind().name());
        append(target, filter.getAlias());
        append(target, filter.getProperty());
        append(target, filter.getOperator() == null ? null : filter.getOperator().name());
        appendValue(target, filter.getValue());
        for (GraphFilter child : filter.getChildren()) appendFilter(target, child);
    }

    /**
     * 规范化过滤值。集合保持业务顺序，Map 按规范化后的 key 排序，数组按索引展开，
     * 从而避免 Map 实现或插入顺序造成同一查询产生不同指纹。
     */
    private static void appendValue(StringBuilder target, Object value) {
        if (value == null) {
            append(target, null);
        } else if (value instanceof Map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(((Map<?, ?>) value).entrySet());
            Collections.sort(entries, new Comparator<Map.Entry<?, ?>>() {
                @Override
                public int compare(Map.Entry<?, ?> left, Map.Entry<?, ?> right) {
                    return canonicalScalar(left.getKey()).compareTo(canonicalScalar(right.getKey()));
                }
            });
            append(target, "map");
            append(target, entries.size());
            for (Map.Entry<?, ?> entry : entries) {
                appendValue(target, entry.getKey());
                appendValue(target, entry.getValue());
            }
        } else if (value instanceof Collection) {
            Collection<?> values = (Collection<?>) value;
            append(target, value instanceof java.util.Set ? "set" : "collection");
            List<Object> ordered = new ArrayList<>(values);
            if (value instanceof java.util.Set) {
                Collections.sort(ordered, new Comparator<Object>() {
                    @Override
                    public int compare(Object left, Object right) {
                        return canonicalScalar(left).compareTo(canonicalScalar(right));
                    }
                });
            }
            append(target, ordered.size());
            for (Object item : ordered) appendValue(target, item);
        } else if (value.getClass().isArray()) {
            append(target, "array");
            append(target, Array.getLength(value));
            for (int i = 0; i < Array.getLength(value); i++) appendValue(target, Array.get(value, i));
        } else {
            append(target, canonicalScalar(value));
        }
    }

    /**
     * 标量同时包含 Java 类型名和值，避免数字 1 和字符串 "1" 指纹相同。
     */
    private static String canonicalScalar(Object value) {
        return value == null ? "null" : value.getClass().getName() + ":" + String.valueOf(value);
    }

    /**
     * 以长度前缀写入字段，消除字段拼接边界歧义。
     */
    private static void append(StringBuilder target, Object value) {
        String text = value == null ? "<null>" : String.valueOf(value);
        target.append(text.length()).append(':').append(text).append(';');
    }
}
