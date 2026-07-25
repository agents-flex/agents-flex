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

import java.util.StringJoiner;

/**
 * 条件树到目标过滤语言的适配器。
 *
 * <p>默认实现输出通用 SQL 风格表达式。不同向量数据库可以按需覆盖字段名、
 * 运算符、值、连接符或分组的渲染方法，而无需修改条件树。</p>
 */
public interface ExpressionAdaptor {

    /** 通用 SQL 风格默认适配器。 */
    ExpressionAdaptor DEFAULT = new ExpressionAdaptor() {
    };

    /** 渲染一个普通条件，不包含它与前置条件之间的连接符。 */
    default String toCondition(Condition condition) {
        return toLeft(condition.left)
            + toOperationSymbol(condition.type)
            + toRight(condition.right);
    }

    /** 渲染条件左操作数，通常是字段名。 */
    default String toLeft(Operand operand) {
        return operand.toExpression(this);
    }

    /** 将条件类型转换为目标存储使用的运算符。 */
    default String toOperationSymbol(ConditionType type) {
        return type.getDefaultSymbol();
    }

    /** 渲染条件右操作数。 */
    default String toRight(Operand operand) {
        return operand.toExpression(this);
    }

    /**
     * 渲染条件值。默认实现会分别处理 BETWEEN、IN/NOT IN 和普通单值。
     * 具体存储应在这里完成必要的转义和类型格式化。
     */
    default String toValue(Condition condition, Object value) {
        // BETWEEN 的 Value 内部保存两个边界值。
        if (condition.getType() == ConditionType.BETWEEN) {
            Object[] values = (Object[]) value;
            return "\"" + values[0] + "\" AND \"" + values[1] + "\"";
        }

        // IN/NOT IN 的 Value 内部保存值数组。
        else if (condition.getType() == ConditionType.IN || condition.getType() == ConditionType.NIN) {
            Object[] values = (Object[]) value;
            StringJoiner stringJoiner = new StringJoiner(",", "(", ")");
            for (Object v : values) {
                if (v != null) {
                    stringJoiner.add("\"" + v + "\"");
                }
            }
            return stringJoiner.toString();
        }

        return value == null ? "" : "\"" + value + "\"";
    }


    /** 渲染条件间的逻辑连接符。 */
    default String toConnector(Connector connector) {
        return connector.getValue();
    }

    /** 渲染分组开始标记。 */
    default String toGroupStart(Group group) {
        return "(";
    }

    /** 渲染分组结束标记。 */
    default String toGroupEnd(Group group) {
        return ")";
    }


}
