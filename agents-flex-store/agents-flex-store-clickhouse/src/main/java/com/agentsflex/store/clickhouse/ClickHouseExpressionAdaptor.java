/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.clickhouse;

import com.agentsflex.core.store.condition.Condition;
import com.agentsflex.core.store.condition.ExpressionAdaptor;
import com.agentsflex.core.store.condition.Group;
import com.agentsflex.core.store.condition.Key;
import com.agentsflex.core.store.condition.Not;
import com.agentsflex.core.store.condition.Value;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.StringJoiner;

/**
 * 将通用条件树转换为 ClickHouse 参数化 SQL。
 *
 * <p>固定字段直接引用表列；metadata 字段通过 JSON_VALUE 读取。数值条件额外使用
 * {@code toFloat64OrNull}，避免字符串形式的数值发生字典序比较。JSON path 只允许经过白名单
 * 校验的字段片段，条件值始终使用 JDBC 参数绑定。</p>
 */
final class ClickHouseExpressionAdaptor implements ExpressionAdaptor {
    private final List<Object> parameters = new ArrayList<>();

    List<Object> getParameters() {
        return Collections.unmodifiableList(parameters);
    }

    @Override
    public String toCondition(Condition condition) {
        if (!(condition.getLeft() instanceof Key) || !(condition.getRight() instanceof Value)) {
            throw new IllegalArgumentException("ClickHouse conditions require a field and a value");
        }
        String field = String.valueOf(((Key) condition.getLeft()).getKey());
        Object value = ((Value) condition.getRight()).getValue();
        switch (condition.getType()) {
            case IS_NULL:
                return fieldExpression(field, null) + " IS NULL";
            case IS_NOT_NULL:
                return fieldExpression(field, null) + " IS NOT NULL";
            case EQ:
                return comparison(field, "=", value);
            case NE:
                return comparison(field, "!=", value);
            case GT:
                return comparison(field, ">", required(value, condition.getType().name()));
            case GE:
                return comparison(field, ">=", required(value, condition.getType().name()));
            case LT:
                return comparison(field, "<", required(value, condition.getType().name()));
            case LE:
                return comparison(field, "<=", required(value, condition.getType().name()));
            case BETWEEN:
                List<Object> bounds = values(value);
                if (bounds.size() != 2 || bounds.get(0) == null || bounds.get(1) == null) {
                    throw new IllegalArgumentException("BETWEEN requires exactly two non-null values");
                }
                ensureSameFamily(bounds);
                parameters.add(normalizeParameter(field, bounds.get(0)));
                parameters.add(normalizeParameter(field, bounds.get(1)));
                return fieldExpression(field, bounds.get(0)) + " BETWEEN ? AND ?";
            case IN:
                return in(field, value, false);
            case NIN:
                return in(field, value, true);
            default:
                throw new IllegalArgumentException("Unsupported ClickHouse condition: " + condition.getType());
        }
    }

    @Override
    public String toGroupStart(Group group) {
        return group instanceof Not ? " (" : "(";
    }

    private String comparison(String field, String operator, Object value) {
        if (value == null) {
            if ("=".equals(operator)) return fieldExpression(field, null) + " IS NULL";
            if ("!=".equals(operator)) return fieldExpression(field, null) + " IS NOT NULL";
            throw new IllegalArgumentException("Null only supports EQ and NE");
        }
        parameters.add(normalizeParameter(field, value));
        return fieldExpression(field, value) + " " + operator + " ?";
    }

    private String in(String field, Object source, boolean negative) {
        List<Object> values = values(source);
        if (values.isEmpty() || values.contains(null)) {
            throw new IllegalArgumentException("IN/NOT IN requires at least one non-null value");
        }
        ensureSameFamily(values);
        StringJoiner placeholders = new StringJoiner(", ", "(", ")");
        for (Object value : values) {
            placeholders.add("?");
            parameters.add(normalizeParameter(field, value));
        }
        return fieldExpression(field, values.get(0)) + (negative ? " NOT IN " : " IN ") + placeholders;
    }

    private String fieldExpression(String field, Object comparisonValue) {
        String normalized = normalizeField(field);
        if (isDocumentField(normalized)) {
            return ClickHouseVectorStore.quoteIdentifier(normalized);
        }
        StringBuilder path = new StringBuilder("$");
        for (String part : normalized.split("\\.")) {
            if (!part.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid ClickHouse metadata field: " + field);
            }
            path.append(".\\\"").append(part).append("\\\"");
        }
        String jsonValue = "JSON_VALUE(`metadata`, '" + path + "')";
        return comparisonValue instanceof Number ? "toFloat64OrNull(" + jsonValue + ")" : jsonValue;
    }

    private Object normalizeParameter(String field, Object value) {
        if (isDocumentField(normalizeField(field))) return String.valueOf(value);
        if (value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Date) return ((Date) value).toInstant().toString();
        if (value instanceof TemporalAccessor || value instanceof Character || value instanceof Enum<?>) {
            return String.valueOf(value);
        }
        return value;
    }

    private String normalizeField(String field) {
        String normalized = field == null ? "" : field.trim();
        if (normalized.startsWith("metadataMap.")) return normalized.substring("metadataMap.".length());
        if (normalized.startsWith("metadata.")) return normalized.substring("metadata.".length());
        return normalized;
    }

    private boolean isDocumentField(String field) {
        return "id".equals(field) || "title".equals(field) || "content".equals(field);
    }

    private Object required(Object value, String operation) {
        if (value == null) throw new IllegalArgumentException(operation + " does not accept null");
        return value;
    }

    private List<Object> values(Object source) {
        List<Object> result = new ArrayList<>();
        if (source instanceof Collection<?>) result.addAll((Collection<?>) source);
        else if (source != null && source.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(source); i++) result.add(Array.get(source, i));
        } else if (source != null) result.add(source);
        return result;
    }

    private void ensureSameFamily(List<Object> values) {
        boolean numeric = values.get(0) instanceof Number;
        for (Object value : values) {
            if ((value instanceof Number) != numeric) {
                throw new IllegalArgumentException("ClickHouse IN/BETWEEN values must use one type family");
            }
        }
    }
}
