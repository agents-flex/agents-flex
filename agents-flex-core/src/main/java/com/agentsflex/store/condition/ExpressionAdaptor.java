/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.store.condition;

public interface ExpressionAdaptor {

    ExpressionAdaptor DEFAULT = new ExpressionAdaptor() {
    };

    default String toLeft(Operand operand) {
        return operand.toExpression(this);
    }

    default String toRight(Operand operand) {
        return operand.toExpression(this);
    }

    default String toValue(ConditionType type, Object value) {
        if (value instanceof Operand) {
            return ((Operand) value).toExpression(this);
        }
//        if (type == ConditionType.IN) {
//            return "(\"" + value + "\")";
//        }
        return value == null ? "" : "\"" + value + "\"";
    }

    default String toConnector(Connector connector) {
        return connector.getValue();
    }

    default String toType(ConditionType type) {
        return type.getDefaultSymbol();
    }

    default String toGroupStart(Group group) {
        return "(";
    }

    default String toGroupEnd(Group group) {
        return ")";
    }
}
