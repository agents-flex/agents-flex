/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.cassandra;

import java.time.Instant;
import java.util.Date;

/** 可映射为 Cassandra 标量列并建立 SAI 索引的 metadata 类型。 */
public enum CassandraMetadataType {
    TEXT("text"),
    INT("int"),
    BIGINT("bigint"),
    DOUBLE("double"),
    BOOLEAN("boolean"),
    TIMESTAMP("timestamp");

    private final String cqlType;

    CassandraMetadataType(String cqlType) {
        this.cqlType = cqlType;
    }

    String getCqlType() {
        return cqlType;
    }

    /** 按首次写入值推断列类型；集合、Map 等复合值需要业务方先转换为标量。 */
    static CassandraMetadataType infer(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return INT;
        }
        if (value instanceof Long) {
            return BIGINT;
        }
        if (value instanceof Float || value instanceof Double) {
            return DOUBLE;
        }
        if (value instanceof Boolean) {
            return BOOLEAN;
        }
        if (value instanceof Date || value instanceof Instant) {
            return TIMESTAMP;
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>) {
            return TEXT;
        }
        throw new IllegalArgumentException("Cassandra metadata does not support value type: "
            + value.getClass().getName());
    }
}
