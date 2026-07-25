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
package com.agentsflex.store.qdrant;

import com.agentsflex.core.store.condition.Condition;
import com.agentsflex.core.store.condition.ConditionType;
import com.agentsflex.core.store.condition.Connector;
import com.agentsflex.core.store.condition.Group;
import com.agentsflex.core.store.condition.Key;
import com.agentsflex.core.store.condition.Not;
import com.agentsflex.core.store.condition.Value;
import io.qdrant.client.grpc.Points;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static io.qdrant.client.ConditionFactory.isNull;
import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.range;

/**
 * 将通用条件链转换为 Qdrant 原生过滤器。
 *
 * <p>同一 AND 段写入 must/must_not，OR 段写入 should；嵌套 {@link Group} 会递归
 * 构建子过滤器，以保留查询条件的分组语义。</p>
 */
final class QdrantConditionBuilder {

    Points.Filter build(Condition condition) {
        if (condition == null) {
            return null;
        }
        List<Points.Condition> orTerms = new ArrayList<>();
        List<Node> andTerms = new ArrayList<>();
        Condition current = condition;
        while (current != null) {
            if (current.checkEffective()) {
                Node node = operand(current);
                Connector connector = current.getConnector();
                if (connector == Connector.AND_NOT || connector == Connector.OR_NOT
                    || connector == Connector.NOT) {
                    node = node.negate();
                }
                if ((connector == Connector.OR || connector == Connector.OR_NOT) && !andTerms.isEmpty()) {
                    orTerms.add(conjunction(andTerms));
                    andTerms = new ArrayList<>();
                }
                andTerms.add(node);
            }
            current = current.getNext();
        }
        if (!andTerms.isEmpty()) {
            orTerms.add(conjunction(andTerms));
        }
        if (orTerms.isEmpty()) {
            return null;
        }
        if (orTerms.size() == 1 && orTerms.get(0).hasFilter()) {
            return orTerms.get(0).getFilter();
        }
        Points.Filter.Builder filter = Points.Filter.newBuilder();
        if (orTerms.size() == 1) {
            filter.addMust(orTerms.get(0));
        } else {
            filter.addAllShould(orTerms);
        }
        return filter.build();
    }

    private Node operand(Condition condition) {
        if (condition instanceof Group) {
            Points.Filter child = build(((Group) condition).getChildCondition());
            if (child == null) {
                throw new IllegalArgumentException("Qdrant filter group cannot be empty");
            }
            Node node = new Node(Points.Condition.newBuilder().setFilter(child).build(), false);
            return condition instanceof Not ? node.negate() : node;
        }
        if (!(condition.getLeft() instanceof Key) || !(condition.getRight() instanceof Value)) {
            throw new IllegalArgumentException("Qdrant conditions require a payload field and a value");
        }
        String field = normalizeField(String.valueOf(((Key) condition.getLeft()).getKey()));
        Object value = ((Value) condition.getRight()).getValue();
        switch (condition.getType()) {
            case EQ:
                return new Node(equality(field, value), false);
            case NE:
                return new Node(equality(field, value), true);
            case GT:
            case GE:
            case LT:
            case LE:
                return new Node(range(field, comparisonRange(condition.getType(), value)), false);
            case BETWEEN:
                List<Object> bounds = values(value);
                if (bounds.size() != 2) {
                    throw new IllegalArgumentException("BETWEEN requires exactly two values");
                }
                return new Node(range(field, Points.Range.newBuilder()
                    .setGte(number(bounds.get(0))).setLte(number(bounds.get(1))).build()), false);
            case IN:
                return new Node(anyOf(field, values(value)), false);
            case NIN:
                return new Node(anyOf(field, values(value)), true);
            default:
                throw new IllegalArgumentException("Unsupported Qdrant condition: " + condition.getType());
        }
    }

    private Points.Condition conjunction(List<Node> nodes) {
        if (nodes.size() == 1 && !nodes.get(0).negated) {
            return nodes.get(0).condition;
        }
        Points.Filter.Builder filter = Points.Filter.newBuilder();
        for (Node node : nodes) {
            if (node.negated) {
                filter.addMustNot(node.condition);
            } else {
                filter.addMust(node.condition);
            }
        }
        return Points.Condition.newBuilder().setFilter(filter).build();
    }

    private Points.Condition anyOf(String field, List<Object> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("IN/NIN requires at least one value");
        }
        if (values.size() == 1) {
            return equality(field, values.get(0));
        }
        Points.Filter.Builder filter = Points.Filter.newBuilder();
        for (Object value : values) {
            filter.addShould(equality(field, value));
        }
        return Points.Condition.newBuilder().setFilter(filter).build();
    }

    private Points.Condition equality(String field, Object value) {
        if (value == null) {
            return isNull(field);
        }
        if (value instanceof Boolean) {
            return match(field, (Boolean) value);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
            || value instanceof Long) {
            return match(field, ((Number) value).longValue());
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            return range(field, Points.Range.newBuilder().setGte(number).setLte(number).build());
        }
        if (value instanceof Character || value instanceof CharSequence) {
            return matchKeyword(field, String.valueOf(value));
        }
        throw new IllegalArgumentException("Unsupported Qdrant filter value: " + value.getClass().getName());
    }

    private Points.Range comparisonRange(ConditionType type, Object value) {
        double number = number(value);
        Points.Range.Builder range = Points.Range.newBuilder();
        switch (type) {
            case GT: return range.setGt(number).build();
            case GE: return range.setGte(number).build();
            case LT: return range.setLt(number).build();
            case LE: return range.setLte(number).build();
            default: throw new IllegalArgumentException("Unsupported Qdrant range: " + type);
        }
    }

    private double number(Object value) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("Qdrant range values must be numeric");
        }
        return ((Number) value).doubleValue();
    }

    private String normalizeField(String field) {
        if ("id".equals(field)) {
            return QdrantVectorStore.ID_PAYLOAD_KEY;
        }
        if ("title".equals(field)) {
            return QdrantVectorStore.TITLE_PAYLOAD_KEY;
        }
        if ("content".equals(field)) {
            return QdrantVectorStore.CONTENT_PAYLOAD_KEY;
        }
        if (field.startsWith("metadataMap.")) {
            field = field.substring("metadataMap.".length());
        } else if (field.startsWith("metadata.")) {
            field = field.substring("metadata.".length());
        }
        if (field.trim().isEmpty()) {
            throw new IllegalArgumentException("Qdrant payload field cannot be blank");
        }
        return field;
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

    private static final class Node {
        private final Points.Condition condition;
        private final boolean negated;

        private Node(Points.Condition condition, boolean negated) {
            this.condition = condition;
            this.negated = negated;
        }

        private Node negate() {
            return new Node(condition, !negated);
        }
    }
}
