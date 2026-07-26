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

/** 条件运算类型及其通用 SQL 风格符号。 */
public enum ConditionType {
    /** 等于。 */
    EQ(" = "),
    /** 不等于。 */
    NE(" != "),
    /** 大于。 */
    GT(" > "),
    /** 大于等于。 */
    GE(" >= "),
    /** 小于。 */
    LT(" < "),
    /** 小于等于。 */
    LE(" <= "),
    /** 属于集合。 */
    IN(" IN "),
    /** 不属于集合。 */
    NIN(" NOT IN "),
    /** 位于两个边界之间。 */
    BETWEEN(" BETWEEN "),
    /** 字段值为空。 */
    IS_NULL(" IS NULL"),
    /** 字段值不为空。 */
    IS_NOT_NULL(" IS NOT NULL"),
    ;

    private final String defaultSymbol;

    ConditionType(String defaultSymbol) {
        this.defaultSymbol = defaultSymbol;
    }

    /** 返回通用 SQL 风格运算符，包含两侧必要空格。 */
    public String getDefaultSymbol() {
        return defaultSymbol;
    }
}
