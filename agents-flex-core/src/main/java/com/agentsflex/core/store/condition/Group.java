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

import com.agentsflex.core.util.StringUtil;

/**
 * 条件分组节点。
 *
 * <p>子条件保存在独立链表中，渲染时由适配器添加分组边界。分组本身仍是
 * {@link Condition}，因此可以继续连接到外层条件链。</p>
 */
public class Group extends Condition {

    /** 分组左括号前的操作符，例如 {@code NOT}。 */
    private String prevOperand = "";
    /** 分组内第一条条件。 */
    private final Condition childCondition;

    public Group(Condition condition) {
        this.childCondition = condition;
    }

    public Group(String prevOperand, Condition childCondition) {
        this.prevOperand = prevOperand;
        this.childCondition = childCondition;
    }

    public Condition getChildCondition() {
        return childCondition;
    }


    @Override
    /** 分组自身有效且至少包含一个有效子条件时，分组才有效。 */
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
    /** 渲染分组及外层链表中的后续条件。 */
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
