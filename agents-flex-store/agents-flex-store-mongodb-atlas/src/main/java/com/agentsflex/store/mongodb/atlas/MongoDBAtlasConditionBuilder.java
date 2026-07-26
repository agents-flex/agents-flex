/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.mongodb.atlas;

import com.agentsflex.core.store.condition.Condition;
import com.agentsflex.core.store.condition.ConditionType;
import com.agentsflex.core.store.condition.Connector;
import com.agentsflex.core.store.condition.Group;
import com.agentsflex.core.store.condition.Key;
import com.agentsflex.core.store.condition.Not;
import com.agentsflex.core.store.condition.Value;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 将通用条件树转换为 MongoDB BSON 查询条件。
 *
 * <p>生成结果既可用于普通 {@code find}，也可直接放入 Atlas
 * {@code $vectorSearch.filter}。分组否定通过德摩根规则转换为相反运算符，避免依赖
 * Atlas Vector Search 预过滤不支持的查询结构。</p>
 */
final class MongoDBAtlasConditionBuilder {

    BsonDocument build(Condition condition) {
        if (condition == null) {
            return new BsonDocument();
        }
        List<BsonDocument> orTerms = new ArrayList<>();
        List<BsonDocument> andTerms = new ArrayList<>();
        Condition current = condition;
        while (current != null) {
            if (current.checkEffective()) {
                BsonDocument operand = buildOperand(current);
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
        return combine("$or", orTerms);
    }

    private BsonDocument buildOperand(Condition condition) {
        if (condition instanceof Group) {
            BsonDocument child = build(((Group) condition).getChildCondition());
            if (child.isEmpty()) {
                throw new IllegalArgumentException("MongoDB filter group cannot be empty");
            }
            return condition instanceof Not ? negate(child) : child;
        }
        if (!(condition.getLeft() instanceof Key) || !(condition.getRight() instanceof Value)) {
            throw new IllegalArgumentException("MongoDB conditions require a field and a value");
        }

        String field = normalizeField(String.valueOf(((Key) condition.getLeft()).getKey()));
        Object value = ((Value) condition.getRight()).getValue();
        switch (condition.getType()) {
            case EQ:
                return fieldCondition(field, "$eq", bsonValue(value));
            case NE:
                return fieldCondition(field, "$ne", bsonValue(value));
            case GT:
                return fieldCondition(field, "$gt", requiredValue(value, condition.getType()));
            case GE:
                return fieldCondition(field, "$gte", requiredValue(value, condition.getType()));
            case LT:
                return fieldCondition(field, "$lt", requiredValue(value, condition.getType()));
            case LE:
                return fieldCondition(field, "$lte", requiredValue(value, condition.getType()));
            case IN:
                return fieldCondition(field, "$in", bsonArray(value));
            case NIN:
                return fieldCondition(field, "$nin", bsonArray(value));
            case BETWEEN:
                List<Object> bounds = values(value);
                if (bounds.size() != 2 || bounds.get(0) == null || bounds.get(1) == null) {
                    throw new IllegalArgumentException("BETWEEN requires exactly two non-null values");
                }
                return new BsonDocument(field, new BsonDocument()
                    .append("$gte", bsonValue(bounds.get(0)))
                    .append("$lte", bsonValue(bounds.get(1))));
            case IS_NULL:
                return fieldCondition(field, "$eq", BsonNull.VALUE);
            case IS_NOT_NULL:
                return fieldCondition(field, "$ne", BsonNull.VALUE);
            default:
                throw new IllegalArgumentException("Unsupported MongoDB condition: " + condition.getType());
        }
    }

    private BsonValue requiredValue(Object value, ConditionType type) {
        if (value == null) {
            throw new IllegalArgumentException(type + " does not accept null");
        }
        return bsonValue(value);
    }

    private BsonDocument fieldCondition(String field, String operator, BsonValue value) {
        return new BsonDocument(field, new BsonDocument(operator, value));
    }

    private BsonDocument combine(String operator, List<BsonDocument> expressions) {
        if (expressions.isEmpty()) {
            return new BsonDocument();
        }
        if (expressions.size() == 1) {
            return expressions.get(0);
        }
        BsonArray values = new BsonArray();
        values.addAll(expressions);
        return new BsonDocument(operator, values);
    }

    /** 对当前 BSON 条件逐层取反，输出 Atlas 预过滤可识别的基本运算符。 */
    private BsonDocument negate(BsonDocument expression) {
        if (expression.size() != 1) {
            throw new IllegalArgumentException("Invalid MongoDB filter expression");
        }
        String key = expression.getFirstKey();
        BsonValue value = expression.get(key);
        if ("$and".equals(key) || "$or".equals(key)) {
            List<BsonDocument> children = new ArrayList<>();
            for (BsonValue child : value.asArray()) {
                children.add(negate(child.asDocument()));
            }
            return combine("$and".equals(key) ? "$or" : "$and", children);
        }
        BsonDocument operation = value.asDocument();
        if (operation.size() == 2 && operation.containsKey("$gte") && operation.containsKey("$lte")) {
            List<BsonDocument> outside = new ArrayList<>();
            outside.add(fieldCondition(key, "$lt", operation.get("$gte")));
            outside.add(fieldCondition(key, "$gt", operation.get("$lte")));
            return combine("$or", outside);
        }
        if (operation.size() != 1) {
            throw new IllegalArgumentException("Cannot negate MongoDB operation: " + operation);
        }
        String operator = operation.getFirstKey();
        return fieldCondition(key, opposite(operator), operation.get(operator));
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
            default: throw new IllegalArgumentException("Cannot negate MongoDB operator: " + operator);
        }
    }

    static String normalizeField(String field) {
        String normalized = field == null ? "" : field.trim();
        if (normalized.startsWith("metadataMap.")) {
            return normalized;
        }
        if (normalized.startsWith("metadata.")) {
            return "metadataMap." + normalized.substring("metadata.".length());
        }
        if ("id".equals(normalized) || "title".equals(normalized) || "content".equals(normalized)) {
            return normalized;
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("MongoDB condition field cannot be blank");
        }
        return "metadataMap." + normalized;
    }

    private BsonArray bsonArray(Object source) {
        List<Object> values = values(source);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("IN/NIN requires at least one value");
        }
        BsonArray result = new BsonArray();
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException("IN/NIN does not accept null items");
            }
            result.add(bsonValue(value));
        }
        return result;
    }

    private List<Object> values(Object source) {
        if (source instanceof Collection) {
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

    private BsonValue bsonValue(Object value) {
        if (value == null) {
            return BsonNull.VALUE;
        }
        if (value instanceof BsonValue) {
            return (BsonValue) value;
        }
        if (value instanceof String || value instanceof Character || value instanceof Enum<?>) {
            return new BsonString(String.valueOf(value));
        }
        if (value instanceof Boolean) {
            return BsonBoolean.valueOf((Boolean) value);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return new BsonInt32(((Number) value).intValue());
        }
        if (value instanceof Long) {
            return new BsonInt64((Long) value);
        }
        if (value instanceof Float || value instanceof Double) {
            return new BsonDouble(((Number) value).doubleValue());
        }
        if (value instanceof BigDecimal) {
            return new BsonDecimal128(new Decimal128((BigDecimal) value));
        }
        if (value instanceof Date) {
            return new BsonDateTime(((Date) value).getTime());
        }
        if (value instanceof ObjectId) {
            return new BsonObjectId((ObjectId) value);
        }
        throw new IllegalArgumentException("Unsupported MongoDB filter value: " + value.getClass().getName());
    }
}
