/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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

import com.agentsflex.util.StringUtil;

public class Group extends Condition {

    private String prevOperand = "";
    private final Condition childCondition;

    public Group(Condition condition) {
        this.childCondition = condition;
    }

    public Group(String prevOperand, Condition childCondition) {
        this.prevOperand = prevOperand;
        this.childCondition = childCondition;
    }


    @Override
    public boolean checkEffective() {
        boolean effective = super.checkEffective();
        if (!effective) {
            return false;
        }
        Condition condition = this.childCondition;
        while (condition != null) {
            if (condition.checkEffective()) {
                return true;
            }
            condition = condition.next;
        }
        return false;
    }


    @Override
    public String toExpression(ExpressionAdaptor adaptor) {
        StringBuilder expr = new StringBuilder();
        if (checkEffective()) {
            String childExpr = childCondition.toExpression(adaptor);
            Condition prevEffectiveCondition = getPrevEffectiveCondition();
            if (prevEffectiveCondition != null && this.connector != null) {
                childExpr = adaptor.toConnector(this.connector) + this.prevOperand + adaptor.toGroupStart(this) + childExpr + adaptor.toGroupEnd(this);
            } else if (StringUtil.hasText(childExpr)) {
                childExpr = this.prevOperand + adaptor.toGroupStart(this) + childExpr + adaptor.toGroupEnd(this);
            }
            expr.append(childExpr);
        }

        if (this.next != null) {
            expr.append(next.toExpression(adaptor));
        }
        return expr.toString();
    }
}
