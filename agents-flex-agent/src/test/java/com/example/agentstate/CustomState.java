/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.example.agentstate;

import java.io.Serializable;

/** 用于验证业务自定义状态类型白名单的测试值对象。 */
public final class CustomState implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String value;

    public CustomState(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
