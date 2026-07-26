/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.cassandra;

import com.agentsflex.core.store.condition.Condition;
import com.agentsflex.core.store.condition.ConditionType;
import com.agentsflex.core.store.condition.Connector;
import com.agentsflex.core.store.condition.Group;
import com.agentsflex.core.store.condition.Key;
import com.agentsflex.core.store.condition.Not;
import com.agentsflex.core.store.condition.Value;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 将通用条件树规划成 Cassandra 可以执行的合取分支（DNF）。
 *
 * <p>Cassandra 5.x 的 ANN 查询不能直接组合 {@code IN}，CQL 也没有通用的
 * {@code OR}。因此这里把 IN 展开成多个 EQ，把 OR 展开成多条查询；Store
 * 执行所有分支后再按文档 ID 去重并进行全局排序。整个过程不使用
 * {@code ALLOW FILTERING}，避免把不完整的客户端过滤伪装成服务端能力。</p>
 */
final class CassandraConditionPlanner {

    private static final int MAX_BRANCHES = 256;

    private CassandraConditionPlanner() {
    }

    static List<List<Predicate>> plan(Condition root) {
        if (root == null) {
            return Collections.singletonList(Collections.emptyList());
        }
        List<List<Predicate>> result = null;
        Condition current = root;
        while (current != null) {
            if (current.checkEffective()) {
                List<List<Predicate>> node = planSingle(current);
                if (result == null) {
                    result = node;
                } else {
                    Connector connector = current.getConnector();
                    if (connector == Connector.AND) {
                        result = and(result, node);
                    } else if (connector == Connector.OR) {
                        result = or(result, node);
                    } else {
                        throw unsupported("connector " + connector);
                    }
                }
            }
            current = current.getNext();
        }
        return result == null ? Collections.singletonList(Collections.emptyList()) : result;
    }

    private static List<List<Predicate>> planSingle(Condition condition) {
        if (condition instanceof Not || condition instanceof Group
            && ((Group) condition).getPrevOperand() != null
            && !((Group) condition).getPrevOperand().trim().isEmpty()) {
            throw unsupported("NOT");
        }
        if (condition instanceof Group) {
            return plan(((Group) condition).getChildCondition());
        }
        if (!(condition.getLeft() instanceof Key) || !(condition.getRight() instanceof Value)) {
            throw unsupported("non key/value condition");
        }

        String field = normalizeField(String.valueOf(((Key) condition.getLeft()).getKey()));
        Object value = ((Value) condition.getRight()).getValue();
        switch (condition.getType()) {
            case EQ:
            case GT:
            case GE:
            case LT:
            case LE:
                return branch(new Predicate(field, condition.getType(), value));
            case BETWEEN:
                List<Object> bounds = values(value);
                if (bounds.size() != 2) {
                    throw new IllegalArgumentException("Cassandra BETWEEN requires exactly two values.");
                }
                List<Predicate> range = new ArrayList<>(2);
                range.add(new Predicate(field, ConditionType.GE, bounds.get(0)));
                range.add(new Predicate(field, ConditionType.LE, bounds.get(1)));
                return Collections.singletonList(range);
            case IN:
                List<List<Predicate>> choices = new ArrayList<>();
                for (Object item : values(value)) {
                    choices.add(Collections.singletonList(
                        new Predicate(field, ConditionType.EQ, item)));
                }
                checkBranchCount(choices.size());
                return choices;
            case NE:
            case NIN:
            case IS_NULL:
            case IS_NOT_NULL:
            default:
                throw unsupported(String.valueOf(condition.getType()));
        }
    }

    private static List<List<Predicate>> branch(Predicate predicate) {
        return Collections.singletonList(Collections.singletonList(predicate));
    }

    private static List<List<Predicate>> and(
        List<List<Predicate>> left,
        List<List<Predicate>> right
    ) {
        checkBranchCount(left.size() * right.size());
        List<List<Predicate>> result = new ArrayList<>(left.size() * right.size());
        for (List<Predicate> first : left) {
            for (List<Predicate> second : right) {
                List<Predicate> branch = new ArrayList<>(first.size() + second.size());
                branch.addAll(first);
                branch.addAll(second);
                result.add(branch);
            }
        }
        return result;
    }

    private static List<List<Predicate>> or(
        List<List<Predicate>> left,
        List<List<Predicate>> right
    ) {
        checkBranchCount(left.size() + right.size());
        List<List<Predicate>> result = new ArrayList<>(left.size() + right.size());
        result.addAll(left);
        result.addAll(right);
        return result;
    }

    private static void checkBranchCount(int count) {
        if (count > MAX_BRANCHES) {
            throw new IllegalArgumentException("Cassandra condition expands to " + count
                + " query branches; maximum is " + MAX_BRANCHES + '.');
        }
    }

    private static List<Object> values(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        List<Object> values = new ArrayList<>();
        if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                values.add(Array.get(value, i));
            }
        } else if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                values.add(item);
            }
        } else {
            values.add(value);
        }
        return values;
    }

    static String normalizeField(String field) {
        if (field == null || field.trim().isEmpty()) {
            throw new IllegalArgumentException("Cassandra condition field must not be blank.");
        }
        String normalized = field.trim();
        if (normalized.startsWith("metadataMap.")) {
            normalized = normalized.substring("metadataMap.".length());
        } else if (normalized.startsWith("metadata.")) {
            normalized = normalized.substring("metadata.".length());
        }
        if ("id".equals(normalized) || "title".equals(normalized) || "content".equals(normalized)) {
            return normalized;
        }
        CassandraVectorStore.validateIdentifier(normalized, "metadata field");
        return "metadata_" + normalized;
    }

    private static IllegalArgumentException unsupported(String feature) {
        return new IllegalArgumentException("Cassandra 5.x CQL/SAI does not support " + feature
            + " in this query. Supported conditions are EQ, GT, GE, LT, LE, BETWEEN, IN, AND and OR.");
    }

    /** 单条 Cassandra 查询中的一个参数化谓词。 */
    static final class Predicate {
        private final String column;
        private final ConditionType type;
        private final Object value;

        Predicate(String column, ConditionType type, Object value) {
            this.column = column;
            this.type = type;
            this.value = value;
        }

        String getColumn() { return column; }
        ConditionType getType() { return type; }
        Object getValue() { return value; }
    }
}
