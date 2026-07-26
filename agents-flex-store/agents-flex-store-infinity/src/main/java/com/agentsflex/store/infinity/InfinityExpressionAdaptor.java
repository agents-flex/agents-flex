/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.infinity;

import com.agentsflex.core.store.condition.Condition;
import com.agentsflex.core.store.condition.ConditionType;
import com.agentsflex.core.store.condition.ExpressionAdaptor;
import com.agentsflex.core.store.condition.Group;
import com.agentsflex.core.store.condition.Key;
import com.agentsflex.core.store.condition.Operand;
import com.agentsflex.core.store.condition.Value;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.StringJoiner;

/**
 * 将 SearchWrapper 条件树转换为 Infinity filter 文本。
 *
 * <p>字段名经过白名单校验，字符串使用 SQL 单引号双写转义。Infinity 0.7 HTTP API 不正确处理
 * BETWEEN 和 IS NULL，因此 BETWEEN 会展开，空值条件会适配为服务端实际返回的 {@code 'Null'} 哨兵。</p>
 */
public class InfinityExpressionAdaptor implements ExpressionAdaptor {

    @Override
    public String toGroupPrefix(Group group) {
        String prefix = group.getPrevOperand();
        return prefix == null || prefix.isEmpty() ? "" : prefix + " ";
    }

    @Override
    public String toLeft(Operand operand) {
        if (!(operand instanceof Key)) {
            throw new IllegalArgumentException("Infinity condition left operand must be a field");
        }
        return InfinityVectorStore.normalizeField(String.valueOf(((Key) operand).getKey()));
    }

    @Override
    public String toCondition(Condition condition) {
        Object value = condition.getRight() instanceof Value
            ? ((Value) condition.getRight()).getValue() : null;
        if (condition.getType() == ConditionType.IS_NULL
            || (condition.getType() == ConditionType.EQ && value == null)) {
            return toLeft(condition.getLeft()) + " = 'Null'";
        }
        if (condition.getType() == ConditionType.IS_NOT_NULL
            || (condition.getType() == ConditionType.NE && value == null)) {
            return toLeft(condition.getLeft()) + " != 'Null'";
        }
        if (condition.getType() == ConditionType.BETWEEN) {
            List<Object> values = values(value);
            if (values.size() != 2 || values.get(0) == null || values.get(1) == null) {
                throw new IllegalArgumentException("BETWEEN requires exactly two non-null values");
            }
            String field = toLeft(condition.getLeft());
            return "(" + field + " >= " + literal(values.get(0))
                + " AND " + field + " <= " + literal(values.get(1)) + ")";
        }
        return ExpressionAdaptor.super.toCondition(condition);
    }

    @Override
    public String toValue(Condition condition, Object value) {
        if (condition.getType() == ConditionType.IN || condition.getType() == ConditionType.NIN) {
            List<Object> values = values(value);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("IN/NOT IN requires at least one value");
            }
            StringJoiner joiner = new StringJoiner(", ", "(", ")");
            for (Object item : values) {
                if (item == null) {
                    throw new IllegalArgumentException("IN/NOT IN does not accept null values");
                }
                joiner.add(literal(item));
            }
            return joiner.toString();
        }
        if (value == null) {
            throw new IllegalArgumentException("Null is only supported by EQ, NE, IS NULL and IS NOT NULL");
        }
        return literal(value);
    }

    static String literal(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Date) {
            return "'" + ((Date) value).toInstant().toString() + "'";
        }
        if (value instanceof TemporalAccessor || value instanceof Character
            || value instanceof CharSequence || value instanceof Enum<?>) {
            return "'" + String.valueOf(value).replace("'", "''") + "'";
        }
        throw new IllegalArgumentException("Unsupported Infinity filter value: " + value.getClass().getName());
    }

    private static List<Object> values(Object source) {
        List<Object> result = new ArrayList<>();
        if (source instanceof Collection<?>) {
            result.addAll((Collection<?>) source);
        } else if (source != null && source.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(source); i++) {
                result.add(Array.get(source, i));
            }
        } else if (source != null) {
            result.add(source);
        }
        return result;
    }
}
