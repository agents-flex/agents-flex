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

public interface ExpressionAdaptor {

    ExpressionAdaptor DEFAULT = new ExpressionAdaptor() {
    };

    default String toCondition(Condition condition) {
        return toLeft(condition.left)
            + toOperationSymbol(condition.type)
            + toRight(condition.right);
    }

    default String toLeft(Operand operand) {
        return operand.toExpression(this);
    }

    default String toOperationSymbol(ConditionType type) {
        return type.getDefaultSymbol();
    }

    default String toRight(Operand operand) {
        return operand.toExpression(this);
    }

    default String toValue(Condition condition, Object value) {
        // between
        if (condition.getType() == ConditionType.BETWEEN) {
            Object[] values = (Object[]) value;
            return "\"" + values[0] + "\" AND \"" + values[1] + "\"";
        }

        // in
        else if (condition.getType() == ConditionType.IN) {
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


    default String toConnector(Connector connector) {
        return connector.getValue();
    }

    default String toGroupStart(Group group) {
        return "(";
    }

    default String toGroupEnd(Group group) {
        return ")";
    }


}
