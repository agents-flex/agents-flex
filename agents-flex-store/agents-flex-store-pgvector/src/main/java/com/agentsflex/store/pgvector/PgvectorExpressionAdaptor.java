/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.pgvector;

import com.agentsflex.core.store.condition.Condition;
import com.agentsflex.core.store.condition.ExpressionAdaptor;
import com.agentsflex.core.store.condition.Group;
import com.agentsflex.core.store.condition.Key;
import com.agentsflex.core.store.condition.Not;
import com.agentsflex.core.store.condition.Value;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * 将条件树转换为 pgvector 查询使用的参数化 SQL。
 *
 * <p>固定文档字段直接引用表列，其他字段通过 JSONB 路径读取；所有条件值收集到
 * 参数列表中，由调用方绑定到 {@link java.sql.PreparedStatement}。</p>
 */
class PgvectorExpressionAdaptor implements ExpressionAdaptor {

    private final List<Object> parameters = new ArrayList<>();

    /** 返回按占位符出现顺序排列的只读参数列表。 */
    List<Object> getParameters() {
        return Collections.unmodifiableList(parameters);
    }

    @Override
    public String toCondition(Condition condition) {
        if (!(condition.getLeft() instanceof Key) || !(condition.getRight() instanceof Value)) {
            throw new IllegalArgumentException("Pgvector conditions require a field and a value.");
        }

        String fieldName = String.valueOf(((Key) condition.getLeft()).getKey());
        Object value = ((Value) condition.getRight()).getValue();
        String expression = fieldExpression(fieldName, firstValue(value));
        switch (condition.getType()) {
            case IS_NULL:
                return expression + " IS NULL";
            case IS_NOT_NULL:
                return expression + " IS NOT NULL";
            case EQ:
                return comparison(expression, "=", value, fieldName);
            case NE:
                return comparison(expression, "<>", value, fieldName);
            case GT:
                return comparison(expression, ">", value, fieldName);
            case GE:
                return comparison(expression, ">=", value, fieldName);
            case LT:
                return comparison(expression, "<", value, fieldName);
            case LE:
                return comparison(expression, "<=", value, fieldName);
            case BETWEEN:
                List<Object> bounds = values(value);
                if (bounds.size() != 2 || bounds.get(0) == null || bounds.get(1) == null) {
                    throw new IllegalArgumentException("BETWEEN requires exactly two non-null values.");
                }
                parameters.add(normalizeValue(fieldName, bounds.get(0)));
                parameters.add(normalizeValue(fieldName, bounds.get(1)));
                return expression + " BETWEEN ? AND ?";
            case IN:
                return in(expression, value, fieldName, false);
            case NIN:
                return in(expression, value, fieldName, true);
            default:
                throw new IllegalArgumentException("Unsupported Pgvector condition: " + condition.getType());
        }
    }

    @Override
    public String toGroupStart(Group group) {
        return group instanceof Not ? " (" : "(";
    }

    private String comparison(String expression, String operator, Object value, String fieldName) {
        if (value == null) {
            if ("=".equals(operator)) {
                return expression + " IS NULL";
            }
            if ("<>".equals(operator)) {
                return expression + " IS NOT NULL";
            }
            throw new IllegalArgumentException("Null is only supported for EQ and NE filters.");
        }
        parameters.add(normalizeValue(fieldName, value));
        return expression + " " + operator + " ?";
    }

    private String in(String expression, Object value, String fieldName, boolean negate) {
        List<Object> values = values(value);
        if (values.isEmpty() || values.contains(null)) {
            throw new IllegalArgumentException("IN/NIN requires at least one non-null value.");
        }
        StringJoiner placeholders = new StringJoiner(", ", "(", ")");
        for (Object item : values) {
            placeholders.add("?");
            parameters.add(normalizeValue(fieldName, item));
        }
        return expression + (negate ? " NOT IN " : " IN ") + placeholders;
    }

    private String fieldExpression(String fieldName, Object sampleValue) {
        if (isDocumentField(fieldName)) {
            return PgvectorVectorStore.quoteIdentifier(fieldName);
        }
        if (fieldName.startsWith("metadataMap.")) {
            fieldName = fieldName.substring("metadataMap.".length());
        } else if (fieldName.startsWith("metadata.")) {
            fieldName = fieldName.substring("metadata.".length());
        }

        String[] path = fieldName.split("\\.");
        if (path.length == 0) {
            throw new IllegalArgumentException("Pgvector metadata field cannot be blank.");
        }
        for (String part : path) {
            if (!part.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid Pgvector metadata field: " + fieldName);
            }
        }
        String expression = "metadata #>> '{" + String.join(",", path) + "}'";
        if (sampleValue instanceof Number) {
            return "CAST(" + expression + " AS numeric)";
        }
        if (sampleValue instanceof Boolean) {
            return "CAST(" + expression + " AS boolean)";
        }
        return expression;
    }

    private Object normalizeValue(String fieldName, Object value) {
        if (isDocumentField(fieldName)) {
            return String.valueOf(value);
        }
        return value;
    }

    private boolean isDocumentField(String fieldName) {
        return "id".equals(fieldName) || "title".equals(fieldName) || "content".equals(fieldName);
    }

    private Object firstValue(Object value) {
        List<Object> values = values(value);
        return values.isEmpty() ? null : values.get(0);
    }

    private List<Object> values(Object value) {
        List<Object> values = new ArrayList<>();
        if (value == null) {
            return values;
        }
        if (value instanceof Collection) {
            values.addAll((Collection<?>) value);
            return values;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                values.add(Array.get(value, i));
            }
            return values;
        }
        values.add(value);
        return values;
    }
}
