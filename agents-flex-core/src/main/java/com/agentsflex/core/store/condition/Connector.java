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
package com.agentsflex.core.store.condition;

/**
 * 条件之间的逻辑连接符。
 *
 * <p>复合连接符用于兼容部分存储过滤语言；具体支持情况由对应的
 * {@link ExpressionAdaptor} 决定。</p>
 */
public enum Connector {


    /**
     * 逻辑与。
     */
    AND(" AND "),

    /**
     * 逻辑与非。
     */
    AND_NOT(" AND NOT "),

    /**
     * 逻辑或。
     */
    OR(" OR "),

    /**
     * 逻辑或非。
     */
    OR_NOT(" OR NOT "),

    /**
     * 逻辑非连接符。
     */
    NOT(" NOT "),
    ;


    private final String value;

    Connector(String value) {
        this.value = value;
    }

    /** 返回默认表达式文本，包含两侧必要空格。 */
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
