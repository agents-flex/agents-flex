/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.opensearch;

import com.agentsflex.core.store.condition.Condition;
import com.agentsflex.core.store.condition.ExpressionAdaptor;
import com.agentsflex.core.store.condition.Group;
import com.agentsflex.core.store.condition.Key;
import com.agentsflex.core.store.condition.Not;
import com.agentsflex.core.store.condition.Value;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

/**
 * 将通用条件树转换为 OpenSearch query string 语法。
 *
 * <p>适配器负责逻辑连接、范围、集合、NULL 判断以及字段和值的转义，转换结果由
 * {@link OpenSearchVectorStore} 放入服务端查询中执行。</p>
 */
class OpenSearchExpressionAdaptor implements ExpressionAdaptor {

    static final OpenSearchExpressionAdaptor DEFAULT = new OpenSearchExpressionAdaptor();

    @Override
    public String toCondition(Condition condition) {
        if (!(condition.getLeft() instanceof Key) || !(condition.getRight() instanceof Value)) {
            throw new IllegalArgumentException("OpenSearch conditions require a field and a value.");
        }

        String field = escapeField(String.valueOf(((Key) condition.getLeft()).getKey()));
        Object value = ((Value) condition.getRight()).getValue();
        switch (condition.getType()) {
            case IS_NULL:
                return "NOT _exists_:" + field;
            case IS_NOT_NULL:
                return "_exists_:" + field;
            case EQ:
                return value == null ? "NOT _exists_:" + field : field + ":" + literal(value);
            case NE:
                return value == null ? "_exists_:" + field : "NOT " + field + ":" + literal(value);
            case GT:
                return field + ":{" + literal(value) + " TO *}";
            case GE:
                return field + ":[" + literal(value) + " TO *]";
            case LT:
                return field + ":{* TO " + literal(value) + "}";
            case LE:
                return field + ":[* TO " + literal(value) + "]";
            case BETWEEN:
                List<Object> bounds = values(value);
                if (bounds.size() != 2) {
                    throw new IllegalArgumentException("BETWEEN requires exactly two values.");
                }
                return field + ":[" + literal(bounds.get(0)) + " TO " + literal(bounds.get(1)) + "]";
            case IN:
                return terms(field, value, false);
            case NIN:
                return terms(field, value, true);
            default:
                throw new IllegalArgumentException("Unsupported OpenSearch condition: " + condition.getType());
        }
    }

    @Override
    public String toGroupStart(Group group) {
        return group instanceof Not ? " (" : "(";
    }

    private String terms(String field, Object value, boolean negate) {
        List<Object> values = values(value);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("IN/NIN requires at least one value.");
        }
        StringJoiner joiner = new StringJoiner(" OR ", field + ":(", ")");
        for (Object item : values) {
            joiner.add(literal(item));
        }
        return negate ? "NOT " + joiner : joiner.toString();
    }

    private String literal(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Null is only supported for EQ and NE filters.");
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        String stringValue = String.valueOf(value)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
        return "\"" + stringValue + "\"";
    }

    private String escapeField(String field) {
        if (field.isEmpty()) {
            throw new IllegalArgumentException("OpenSearch condition field cannot be blank.");
        }
        return field.replace("\\", "\\\\").replace(":", "\\:");
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
