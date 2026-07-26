/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.qcloud;

import com.agentsflex.core.store.condition.Condition;
import com.agentsflex.core.store.condition.ConditionType;
import com.agentsflex.core.store.condition.Connector;
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
import java.util.regex.Pattern;

/** 将通用条件树转换为腾讯云 VectorDB filter 表达式。 */
final class QCloudExpressionAdaptor implements ExpressionAdaptor {

    static final QCloudExpressionAdaptor DEFAULT = new QCloudExpressionAdaptor();

    private static final Pattern FIELD_NAME = Pattern.compile(
        "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    private QCloudExpressionAdaptor() {
    }

    @Override
    public String toCondition(Condition condition) {
        if (!(condition.getLeft() instanceof Key) || !(condition.getRight() instanceof Value)) {
            throw new IllegalArgumentException("Tencent VectorDB conditions require a field and a value.");
        }

        String field = field(((Key) condition.getLeft()).getKey());
        Object value = ((Value) condition.getRight()).getValue();
        if (condition.getType() == ConditionType.IS_NULL
            || condition.getType() == ConditionType.IS_NOT_NULL) {
            throw new IllegalArgumentException(
                "Tencent VectorDB filters do not support NULL predicates.");
        }
        if (value == null) {
            throw new IllegalArgumentException("Tencent VectorDB filters do not support NULL values.");
        }

        if (condition.getType() == ConditionType.BETWEEN) {
            List<Object> bounds = values(value);
            if (bounds.size() != 2) {
                throw new IllegalArgumentException("BETWEEN requires exactly two values.");
            }
            return "(" + field + " >= " + literal(bounds.get(0))
                + " and " + field + " <= " + literal(bounds.get(1)) + ")";
        }

        return field + operation(condition.getType()) + renderValue(condition.getType(), value);
    }

    @Override
    public String toConnector(Connector connector) {
        if (connector == Connector.AND) {
            return " and ";
        }
        if (connector == Connector.OR) {
            return " or ";
        }
        throw new IllegalArgumentException("Unsupported Tencent VectorDB connector: " + connector);
    }

    @Override
    public String toGroupStart(Group group) {
        if (group instanceof Not) {
            throw new IllegalArgumentException(
                "Tencent VectorDB filters do not support unary NOT groups.");
        }
        return "(";
    }

    private String operation(ConditionType type) {
        switch (type) {
            case EQ:
                return " = ";
            case NE:
                return " != ";
            case GT:
                return " > ";
            case GE:
                return " >= ";
            case LT:
                return " < ";
            case LE:
                return " <= ";
            case IN:
                return " in ";
            case NIN:
                return " not in ";
            default:
                throw new IllegalArgumentException("Unsupported Tencent VectorDB condition: " + type);
        }
    }

    private String renderValue(ConditionType type, Object value) {
        if (type == ConditionType.IN || type == ConditionType.NIN) {
            List<Object> items = values(value);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("IN/NOT IN requires at least one value.");
            }
            StringJoiner joiner = new StringJoiner(",", "(", ")");
            for (Object item : items) {
                joiner.add(literal(item));
            }
            return joiner.toString();
        }
        return literal(value);
    }

    private String literal(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Character || value instanceof CharSequence) {
            String text = String.valueOf(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
            return "\"" + text + "\"";
        }
        throw new IllegalArgumentException(
            "Unsupported Tencent VectorDB filter value type: " + value.getClass().getName());
    }

    private String field(Object value) {
        String field = value == null ? "" : String.valueOf(value);
        if (!FIELD_NAME.matcher(field).matches()) {
            throw new IllegalArgumentException("Invalid Tencent VectorDB filter field: " + field);
        }
        return field;
    }

    private List<Object> values(Object value) {
        List<Object> result = new ArrayList<>();
        if (value instanceof Collection) {
            result.addAll((Collection<?>) value);
        } else if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                result.add(Array.get(value, i));
            }
        } else if (value != null) {
            result.add(value);
        }
        for (Object item : result) {
            if (item == null) {
                throw new IllegalArgumentException("Tencent VectorDB filter values cannot contain NULL.");
            }
        }
        return result;
    }
}
