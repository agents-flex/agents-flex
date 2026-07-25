/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.store.redis;

import com.agentsflex.core.store.condition.Condition;
import com.agentsflex.core.store.condition.ConditionType;
import com.agentsflex.core.store.condition.Connector;
import com.agentsflex.core.store.condition.ExpressionAdaptor;
import com.agentsflex.core.store.condition.Key;
import com.agentsflex.core.store.condition.Value;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

class RedisExpressionAdaptor implements ExpressionAdaptor {

    private final String indexName;
    private final RedisVectorStore store;

    RedisExpressionAdaptor(String indexName, RedisVectorStore store) {
        this.indexName = indexName;
        this.store = store;
    }

    @Override
    public String toCondition(Condition condition) {
        if (!(condition.getLeft() instanceof Key) || !(condition.getRight() instanceof Value)) {
            throw new IllegalArgumentException("Redis conditions require a metadata field and a value.");
        }

        String fieldName = String.valueOf(((Key) condition.getLeft()).getKey());
        store.validateMetadataFieldName(fieldName);
        Object value = ((Value) condition.getRight()).getValue();
        RedisVectorStore.MetadataFieldType fieldType = inferFieldType(condition.getType(), value);
        store.createMetadataFieldIfNecessary(indexName, fieldName, fieldType);

        String alias = store.metadataFieldAlias(fieldName);
        if (fieldType == RedisVectorStore.MetadataFieldType.NUMERIC) {
            return numericCondition(alias, condition.getType(), value);
        }
        return tagCondition(alias, condition.getType(), value);
    }

    @Override
    public String toConnector(Connector connector) {
        switch (connector) {
            case OR:
                return " | ";
            case AND_NOT:
                return " -";
            case OR_NOT:
                return " | -";
            case NOT:
                return " -";
            case AND:
            default:
                return " ";
        }
    }

    private RedisVectorStore.MetadataFieldType inferFieldType(ConditionType type, Object value) {
        if (type == ConditionType.GT || type == ConditionType.GE || type == ConditionType.LT
            || type == ConditionType.LE || type == ConditionType.BETWEEN) {
            return RedisVectorStore.MetadataFieldType.NUMERIC;
        }
        Object firstValue = firstValue(value);
        return firstValue instanceof Number
            ? RedisVectorStore.MetadataFieldType.NUMERIC
            : RedisVectorStore.MetadataFieldType.TAG;
    }

    private String numericCondition(String alias, ConditionType type, Object value) {
        switch (type) {
            case EQ:
                return numericRange(alias, value, false, value, false);
            case NE:
                return "-" + numericRange(alias, value, false, value, false);
            case GT:
                return numericRange(alias, value, true, "+inf", false);
            case GE:
                return numericRange(alias, value, false, "+inf", false);
            case LT:
                return numericRange(alias, "-inf", false, value, true);
            case LE:
                return numericRange(alias, "-inf", false, value, false);
            case BETWEEN:
                List<Object> betweenValues = values(value);
                if (betweenValues.size() != 2) {
                    throw new IllegalArgumentException("BETWEEN requires exactly two values.");
                }
                return numericRange(alias, betweenValues.get(0), false, betweenValues.get(1), false);
            case IN:
                return numericAlternatives(alias, value, false);
            case NIN:
                return numericAlternatives(alias, value, true);
            default:
                throw new IllegalArgumentException("Unsupported numeric condition: " + type);
        }
    }

    private String tagCondition(String alias, ConditionType type, Object value) {
        switch (type) {
            case EQ:
                return tagValues(alias, value, false);
            case NE:
                return tagValues(alias, value, true);
            case IN:
                return tagValues(alias, value, false);
            case NIN:
                return tagValues(alias, value, true);
            default:
                throw new IllegalArgumentException("Condition " + type + " requires numeric metadata in RedisVectorStore.");
        }
    }

    private String numericAlternatives(String alias, Object value, boolean negate) {
        List<String> expressions = new ArrayList<>();
        for (Object item : values(value)) {
            expressions.add(numericRange(alias, item, false, item, false));
        }
        if (expressions.isEmpty()) {
            throw new IllegalArgumentException("IN/NIN requires at least one value.");
        }
        String expression = "(" + String.join(" | ", expressions) + ")";
        return negate ? "-" + expression : expression;
    }

    private String numericRange(String alias, Object min, boolean exclusiveMin, Object max, boolean exclusiveMax) {
        return "@" + alias + ":[" + rangeValue(min, exclusiveMin) + " " + rangeValue(max, exclusiveMax) + "]";
    }

    private String rangeValue(Object value, boolean exclusive) {
        String stringValue = String.valueOf(value);
        if (!"-inf".equals(stringValue) && !"+inf".equals(stringValue)) {
            Double.parseDouble(stringValue);
        }
        return exclusive ? "(" + stringValue : stringValue;
    }

    private String tagValues(String alias, Object value, boolean negate) {
        List<Object> values = values(value);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("EQ/NE/IN/NIN requires at least one value.");
        }
        List<String> escapedValues = new ArrayList<>(values.size());
        for (Object item : values) {
            escapedValues.add(escapeTagValue(String.valueOf(item)));
        }
        String expression = "@" + alias + ":{" + String.join("|", escapedValues) + "}";
        return negate ? "-" + expression : expression;
    }

    private String escapeTagValue(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_') {
                escaped.append(ch);
            } else {
                escaped.append('\\').append(ch);
            }
        }
        return escaped.toString();
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
