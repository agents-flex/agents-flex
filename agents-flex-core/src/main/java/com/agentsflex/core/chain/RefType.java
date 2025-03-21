package com.agentsflex.core.chain;

import com.alibaba.fastjson.annotation.JSONType;

@JSONType(typeName = "RefType")
public enum RefType {
    REF("ref"),
    FIXED("fixed"),
    INPUT("input"),
    ;

    private final String value;

    RefType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    public static RefType ofValue(String value) {
        for (RefType type : RefType.values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
