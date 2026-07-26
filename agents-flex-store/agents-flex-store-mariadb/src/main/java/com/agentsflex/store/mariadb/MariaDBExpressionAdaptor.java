/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.mariadb;

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

/** 将通用条件树转换为使用参数绑定的 MariaDB SQL 条件。 */
class MariaDBExpressionAdaptor implements ExpressionAdaptor {

    private final List<Object> parameters = new ArrayList<>();

    List<Object> getParameters() {
        return Collections.unmodifiableList(parameters);
    }

    @Override
    public String toCondition(Condition condition) {
        if (!(condition.getLeft() instanceof Key) || !(condition.getRight() instanceof Value)) {
            throw new IllegalArgumentException("MariaDB conditions require a field and a value.");
        }
        String fieldName = String.valueOf(((Key) condition.getLeft()).getKey());
        Object value = ((Value) condition.getRight()).getValue();
        String expression = fieldExpression(fieldName);
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
                throw new IllegalArgumentException("Unsupported MariaDB condition: " + condition.getType());
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

    private String fieldExpression(String fieldName) {
        if (isDocumentField(fieldName)) {
            return MariaDBVectorStore.quoteIdentifier(fieldName);
        }
        String normalized = normalizeMetadataField(fieldName);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("MariaDB metadata field cannot be blank.");
        }
        String[] path = normalized.split("\\.");
        StringBuilder jsonPath = new StringBuilder("$");
        for (String part : path) {
            if (!part.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid MariaDB metadata field: " + normalized);
            }
            jsonPath.append(".\"").append(part).append("\"");
        }
        return "JSON_VALUE(`metadata`, '" + jsonPath + "')";
    }

    private String normalizeMetadataField(String fieldName) {
        if (fieldName.startsWith("metadataMap.")) {
            return fieldName.substring("metadataMap.".length());
        }
        if (fieldName.startsWith("metadata.")) {
            return fieldName.substring("metadata.".length());
        }
        return fieldName;
    }

    private Object normalizeValue(String fieldName, Object value) {
        return isDocumentField(fieldName) ? String.valueOf(value) : value;
    }

    private boolean isDocumentField(String fieldName) {
        return "id".equals(fieldName) || "title".equals(fieldName) || "content".equals(fieldName);
    }

    private List<Object> values(Object value) {
        List<Object> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (value instanceof Collection) {
            result.addAll((Collection<?>) value);
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                result.add(Array.get(value, i));
            }
        } else {
            result.add(value);
        }
        return result;
    }
}
