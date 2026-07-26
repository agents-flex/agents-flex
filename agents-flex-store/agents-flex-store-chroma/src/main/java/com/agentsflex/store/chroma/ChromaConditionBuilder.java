/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.store.chroma;

import com.agentsflex.core.store.condition.Condition;
import com.agentsflex.core.store.condition.ConditionType;
import com.agentsflex.core.store.condition.Connector;
import com.agentsflex.core.store.condition.Group;
import com.agentsflex.core.store.condition.Key;
import com.agentsflex.core.store.condition.Not;
import com.agentsflex.core.store.condition.Value;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将通用条件树转换为 Chroma {@code where} 过滤对象。
 *
 * <p>连续 AND 条件组合为 {@code $and}，OR 分段组合为 {@code $or}；分组和 NOT
 * 递归转换，以保留原始布尔逻辑。</p>
 */
@SuppressWarnings("unchecked")
final class ChromaConditionBuilder {

    Map<String, Object> build(Condition condition) {
        if (condition == null) {
            return null;
        }
        List<Map<String, Object>> orTerms = new ArrayList<>();
        List<Map<String, Object>> andTerms = new ArrayList<>();
        Condition current = condition;
        while (current != null) {
            if (current.checkEffective()) {
                Map<String, Object> operand = buildOperand(current);
                Connector connector = current.getConnector();
                if (connector == Connector.AND_NOT || connector == Connector.OR_NOT
                    || connector == Connector.NOT) {
                    operand = negate(operand);
                }
                if ((connector == Connector.OR || connector == Connector.OR_NOT) && !andTerms.isEmpty()) {
                    orTerms.add(combine("$and", andTerms));
                    andTerms = new ArrayList<>();
                }
                andTerms.add(operand);
            }
            current = current.getNext();
        }
        if (!andTerms.isEmpty()) {
            orTerms.add(combine("$and", andTerms));
        }
        if (orTerms.isEmpty()) {
            return null;
        }
        return combine("$or", orTerms);
    }

    private Map<String, Object> buildOperand(Condition condition) {
        if (condition instanceof Group) {
            Map<String, Object> group = build(((Group) condition).getChildCondition());
            if (group == null) {
                throw new IllegalArgumentException("Chroma filter group cannot be empty");
            }
            return condition instanceof Not ? negate(group) : group;
        }
        if (!(condition.getLeft() instanceof Key) || !(condition.getRight() instanceof Value)) {
            throw new IllegalArgumentException("Chroma conditions require a metadata field and a value");
        }
        String field = normalizeField(String.valueOf(((Key) condition.getLeft()).getKey()));
        Object value = ((Value) condition.getRight()).getValue();
        if (condition.getType() == ConditionType.IS_NULL
            || condition.getType() == ConditionType.IS_NOT_NULL) {
            throw new IllegalArgumentException("Chroma metadata filters do not support null predicates");
        }
        if (value == null) {
            throw new IllegalArgumentException("Chroma metadata filters do not support null values");
        }

        if (condition.getType() == ConditionType.BETWEEN) {
            List<Object> bounds = values(value);
            if (bounds.size() != 2 || bounds.get(0) == null || bounds.get(1) == null) {
                throw new IllegalArgumentException("BETWEEN requires exactly two non-null values");
            }
            List<Map<String, Object>> comparisons = new ArrayList<>();
            comparisons.add(fieldCondition(field, "$gte", scalar(bounds.get(0))));
            comparisons.add(fieldCondition(field, "$lte", scalar(bounds.get(1))));
            return combine("$and", comparisons);
        }

        String operator = operator(condition.getType());
        Object normalizedValue;
        if (condition.getType() == ConditionType.IN || condition.getType() == ConditionType.NIN) {
            List<Object> items = values(value);
            if (items.isEmpty()) {
                throw new IllegalArgumentException("IN/NIN requires at least one value");
            }
            normalizedValue = new ArrayList<>();
            for (Object item : items) {
                ((List<Object>) normalizedValue).add(scalar(item));
            }
        } else {
            normalizedValue = scalar(value);
        }
        return fieldCondition(field, operator, normalizedValue);
    }

    private Map<String, Object> negate(Map<String, Object> expression) {
        if (expression.size() != 1) {
            throw new IllegalArgumentException("Invalid Chroma filter expression");
        }
        Map.Entry<String, Object> entry = expression.entrySet().iterator().next();
        if ("$and".equals(entry.getKey()) || "$or".equals(entry.getKey())) {
            List<Map<String, Object>> negated = new ArrayList<>();
            for (Map<String, Object> child : (List<Map<String, Object>>) entry.getValue()) {
                negated.add(negate(child));
            }
            return combine("$and".equals(entry.getKey()) ? "$or" : "$and", negated);
        }
        Map<String, Object> operation = (Map<String, Object>) entry.getValue();
        Map.Entry<String, Object> operator = operation.entrySet().iterator().next();
        return fieldCondition(entry.getKey(), opposite(operator.getKey()), operator.getValue());
    }

    private String opposite(String operator) {
        switch (operator) {
            case "$eq": return "$ne";
            case "$ne": return "$eq";
            case "$gt": return "$lte";
            case "$gte": return "$lt";
            case "$lt": return "$gte";
            case "$lte": return "$gt";
            case "$in": return "$nin";
            case "$nin": return "$in";
            default: throw new IllegalArgumentException("Cannot negate Chroma operator: " + operator);
        }
    }

    private String operator(ConditionType type) {
        switch (type) {
            case EQ: return "$eq";
            case NE: return "$ne";
            case GT: return "$gt";
            case GE: return "$gte";
            case LT: return "$lt";
            case LE: return "$lte";
            case IN: return "$in";
            case NIN: return "$nin";
            default: throw new IllegalArgumentException("Unsupported Chroma condition: " + type);
        }
    }

    private Map<String, Object> fieldCondition(String field, String operator, Object value) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put(operator, value);
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put(field, operation);
        return condition;
    }

    private Map<String, Object> combine(String operator, List<Map<String, Object>> expressions) {
        if (expressions.size() == 1) {
            return expressions.get(0);
        }
        Map<String, Object> combined = new LinkedHashMap<>();
        combined.put(operator, expressions);
        return combined;
    }

    private String normalizeField(String field) {
        if (field.startsWith("metadataMap.")) {
            field = field.substring("metadataMap.".length());
        } else if (field.startsWith("metadata.")) {
            field = field.substring("metadata.".length());
        }
        if (field.trim().isEmpty()) {
            throw new IllegalArgumentException("Chroma metadata field cannot be blank");
        }
        return field;
    }

    private Object scalar(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("Unsupported Chroma filter value: "
            + (value == null ? "null" : value.getClass().getName()));
    }

    private List<Object> values(Object value) {
        if (value instanceof Collection) {
            return new ArrayList<>((Collection<?>) value);
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                result.add(Array.get(value, i));
            }
            return result;
        }
        return Collections.singletonList(value);
    }
}
