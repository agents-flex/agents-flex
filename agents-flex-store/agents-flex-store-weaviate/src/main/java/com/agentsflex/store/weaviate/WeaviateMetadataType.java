/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.weaviate;

import io.weaviate.client.v1.schema.model.DataType;

/**
 * 可显式声明的 Weaviate metadata 属性类型。
 *
 * <p>显式声明适合值可能始终为 null、首次写入为空数组，或者生产环境要求预先固定 schema 的字段。</p>
 */
public enum WeaviateMetadataType {
    TEXT(DataType.TEXT),
    INT(DataType.INT),
    NUMBER(DataType.NUMBER),
    BOOLEAN(DataType.BOOLEAN),
    DATE(DataType.DATE),
    TEXT_ARRAY(DataType.TEXT_ARRAY),
    INT_ARRAY(DataType.INT_ARRAY),
    NUMBER_ARRAY(DataType.NUMBER_ARRAY),
    BOOLEAN_ARRAY(DataType.BOOLEAN_ARRAY),
    DATE_ARRAY(DataType.DATE_ARRAY);

    private final String dataType;

    WeaviateMetadataType(String dataType) {
        this.dataType = dataType;
    }

    public String getDataType() {
        return dataType;
    }
}
