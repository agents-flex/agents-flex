/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.weaviate;

import com.agentsflex.core.store.condition.Condition;
import com.agentsflex.core.store.condition.ConditionType;
import com.agentsflex.core.store.condition.Connector;
import com.agentsflex.core.store.condition.Group;
import com.agentsflex.core.store.condition.Key;
import com.agentsflex.core.store.condition.Not;
import com.agentsflex.core.store.condition.Value;
import io.weaviate.client.v1.filters.Operator;
import io.weaviate.client.v1.filters.WhereFilter;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 将 Agents-Flex 通用条件树转换为 Weaviate 原生 {@link WhereFilter}。
 *
 * <p>IN、NOT IN 和 BETWEEN 会展开成基本条件组合，因此不会把 SQL 文本直接拼接到 GraphQL，
 * 同时保留嵌套 AND、OR、NOT 的原始语义。</p>
 */
final class WeaviateConditionBuilder {

    WhereFilter build(Condition condition) {
        return build(condition, Collections.emptyMap());
    }

    WhereFilter build(Condition condition, Map<String, String> schema) {
        if (condition == null) {
            return null;
        }
        List<WhereFilter> orTerms = new ArrayList<>();
        List<WhereFilter> andTerms = new ArrayList<>();
        Condition current = condition;
        while (current != null) {
            if (current.checkEffective()) {
                WhereFilter operand = buildOperand(current, schema);
                Connector connector = current.getConnector();
                if (connector == Connector.AND_NOT || connector == Connector.OR_NOT
                    || connector == Connector.NOT) {
                    operand = negate(operand);
                }
                if ((connector == Connector.OR || connector == Connector.OR_NOT) && !andTerms.isEmpty()) {
                    orTerms.add(combine(Operator.And, andTerms));
                    andTerms = new ArrayList<>();
                }
                andTerms.add(operand);
            }
            current = current.getNext();
        }
        if (!andTerms.isEmpty()) {
            orTerms.add(combine(Operator.And, andTerms));
        }
        return combine(Operator.Or, orTerms);
    }

    private WhereFilter buildOperand(Condition condition, Map<String, String> schema) {
        if (condition instanceof Group) {
            WhereFilter child = build(((Group) condition).getChildCondition(), schema);
            if (child == null) {
                throw new IllegalArgumentException("Weaviate filter group cannot be empty");
            }
            return condition instanceof Not ? negate(child) : child;
        }
        if (!(condition.getLeft() instanceof Key) || !(condition.getRight() instanceof Value)) {
            throw new IllegalArgumentException("Weaviate conditions require a field and a value");
        }

        String field = normalizeField(String.valueOf(((Key) condition.getLeft()).getKey()));
        Object value = ((Value) condition.getRight()).getValue();
        switch (condition.getType()) {
            case EQ:
                return value == null ? nullFilter(field, true) : comparison(field, Operator.Equal, value, schema);
            case NE:
                return value == null ? nullFilter(field, false) : comparison(field, Operator.NotEqual, value, schema);
            case GT:
                return comparison(field, Operator.GreaterThan, requiredValue(value, condition.getType()), schema);
            case GE:
                return comparison(field, Operator.GreaterThanEqual, requiredValue(value, condition.getType()), schema);
            case LT:
                return comparison(field, Operator.LessThan, requiredValue(value, condition.getType()), schema);
            case LE:
                return comparison(field, Operator.LessThanEqual, requiredValue(value, condition.getType()), schema);
            case BETWEEN:
                List<Object> bounds = values(value);
                if (bounds.size() != 2 || bounds.get(0) == null || bounds.get(1) == null) {
                    throw new IllegalArgumentException("BETWEEN requires exactly two non-null values");
                }
                return combine(Operator.And, Arrays.asList(
                    comparison(field, Operator.GreaterThanEqual, bounds.get(0), schema),
                    comparison(field, Operator.LessThanEqual, bounds.get(1), schema)));
            case IN:
                return collectionCondition(field, value, false, schema);
            case NIN:
                return collectionCondition(field, value, true, schema);
            case IS_NULL:
                return nullFilter(field, true);
            case IS_NOT_NULL:
                return nullFilter(field, false);
            default:
                throw new IllegalArgumentException("Unsupported Weaviate condition: " + condition.getType());
        }
    }

    private WhereFilter collectionCondition(
        String field,
        Object source,
        boolean negative,
        Map<String, String> schema
    ) {
        List<Object> values = values(source);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("IN/NOT IN requires at least one value");
        }
        List<WhereFilter> filters = new ArrayList<>();
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException("IN/NOT IN does not accept null items");
            }
            filters.add(comparison(field, negative ? Operator.NotEqual : Operator.Equal, value, schema));
        }
        return combine(negative ? Operator.And : Operator.Or, filters);
    }

    /** Weaviate 使用 IsNull + boolean 表达 IS NULL 与 IS NOT NULL。 */
    private WhereFilter nullFilter(String field, boolean isNull) {
        return WhereFilter.builder().path(field).operator("IsNull").valueBoolean(isNull).build();
    }

    private WhereFilter comparison(
        String field,
        String operator,
        Object value,
        Map<String, String> schema
    ) {
        WhereFilter.WhereFilterBuilder builder = WhereFilter.builder().path(field).operator(operator);
        String dataType = schema.get(field);
        if (value instanceof Boolean) {
            return builder.valueBoolean((Boolean) value).build();
        }
        if (io.weaviate.client.v1.schema.model.DataType.INT.equals(dataType)
            || value instanceof Byte || value instanceof Short || value instanceof Integer) {
            long integer = ((Number) value).longValue();
            if (integer < Integer.MIN_VALUE || integer > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Weaviate Java Client only supports 32-bit valueInt filters");
            }
            return builder.valueInt((int) integer).build();
        }
        if (value instanceof Number) {
            return builder.valueNumber(((Number) value).doubleValue()).build();
        }
        if (value instanceof Date) {
            return builder.valueDate((Date) value).build();
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>) {
            return builder.valueText(String.valueOf(value)).build();
        }
        throw new IllegalArgumentException("Unsupported Weaviate filter value: " + value.getClass().getName());
    }

    private Object requiredValue(Object value, ConditionType type) {
        if (value == null) {
            throw new IllegalArgumentException(type + " does not accept null");
        }
        return value;
    }

    private WhereFilter combine(String operator, List<WhereFilter> filters) {
        if (filters.isEmpty()) {
            return null;
        }
        if (filters.size() == 1) {
            return filters.get(0);
        }
        return WhereFilter.builder().operator(operator).operands(filters.toArray(new WhereFilter[0])).build();
    }

    private WhereFilter negate(WhereFilter filter) {
        return WhereFilter.builder().operator(Operator.Not).operands(filter).build();
    }

    private List<Object> values(Object source) {
        if (source instanceof Collection<?>) {
            return new ArrayList<>((Collection<?>) source);
        }
        if (source != null && source.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int index = 0; index < Array.getLength(source); index++) {
                result.add(Array.get(source, index));
            }
            return result;
        }
        return source == null ? Collections.emptyList() : Collections.singletonList(source);
    }

    static String normalizeField(String field) {
        String normalized = field == null ? "" : field.trim();
        if ("id".equals(normalized) || "agentsFlexId".equals(normalized)) {
            return WeaviateVectorStore.ID_PROPERTY;
        }
        if ("title".equals(normalized) || "content".equals(normalized)) {
            return normalized;
        }
        if (normalized.startsWith("metadataMap.")) {
            normalized = normalized.substring("metadataMap.".length());
        } else if (normalized.startsWith("metadata.")) {
            normalized = normalized.substring("metadata.".length());
        }
        return WeaviateVectorStore.metadataProperty(normalized);
    }
}
