/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.infinity;

/** 可显式声明的 Infinity metadata 标量列类型。 */
public enum InfinityMetadataType {
    VARCHAR("varchar"),
    INTEGER("integer"),
    BIGINT("bigint"),
    FLOAT("float"),
    DOUBLE("double"),
    BOOLEAN("boolean");

    private final String columnType;

    InfinityMetadataType(String columnType) {
        this.columnType = columnType;
    }

    public String getColumnType() { return columnType; }
}
