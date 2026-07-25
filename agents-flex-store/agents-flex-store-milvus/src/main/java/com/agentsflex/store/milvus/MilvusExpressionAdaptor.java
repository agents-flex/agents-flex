package com.agentsflex.store.milvus;

import com.agentsflex.core.store.condition.Condition;
import com.agentsflex.core.store.condition.ConditionType;
import com.agentsflex.core.store.condition.ExpressionAdaptor;
import com.agentsflex.core.store.condition.Value;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

/**
 * 将通用条件树转换为 Milvus 标量过滤表达式。
 *
 * <p>负责 Milvus 运算符差异、字符串转义、IN/NOT IN 数组、BETWEEN 展开和
 * NULL 判断，并保持条件分组结构。</p>
 */
public class MilvusExpressionAdaptor implements ExpressionAdaptor {

    public static final MilvusExpressionAdaptor DEFAULT = new MilvusExpressionAdaptor();

    @Override
    public String toOperationSymbol(ConditionType type) {
        switch (type) {
            case EQ:
                return " == ";
            case IN:
                return " in ";
            case NIN:
                return " not in ";
            default:
                return type.getDefaultSymbol();
        }
    }

    @Override
    public String toCondition(Condition condition) {
        if (condition.getRight() instanceof Value
            && ((Value) condition.getRight()).getValue() == null) {
            if (condition.getType() == ConditionType.EQ) {
                return toLeft(condition.getLeft()) + " is null";
            }
            if (condition.getType() == ConditionType.NE) {
                return toLeft(condition.getLeft()) + " is not null";
            }
            throw new IllegalArgumentException("Null is only supported for EQ and NE filters");
        }
        if (condition.getType() == ConditionType.BETWEEN) {
            Object value = ((Value) condition.getRight()).getValue();
            List<Object> values = values(value);
            if (values.size() != 2) {
                throw new IllegalArgumentException("BETWEEN requires exactly two values");
            }
            String field = toLeft(condition.getLeft());
            return "(" + field + " >= " + toLiteral(values.get(0))
                + " and " + field + " <= " + toLiteral(values.get(1)) + ")";
        }
        return ExpressionAdaptor.super.toCondition(condition);
    }

    @Override
    public String toValue(Condition condition, Object value) {
        if (condition.getType() == ConditionType.IN || condition.getType() == ConditionType.NIN) {
            List<Object> values = values(value);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("IN/NIN requires at least one value");
            }
            StringJoiner joiner = new StringJoiner(", ", "[", "]");
            for (Object item : values) {
                joiner.add(toLiteral(item));
            }
            return joiner.toString();
        }
        return toLiteral(value);
    }

    static String toLiteral(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Character || value instanceof CharSequence) {
            String stringValue = String.valueOf(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
            return "\"" + stringValue + "\"";
        }
        throw new IllegalArgumentException("Unsupported Milvus filter value type: " + value.getClass().getName());
    }

    private static List<Object> values(Object value) {
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
