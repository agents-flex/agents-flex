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
 * 条件树中的普通谓词节点，同时也是同级条件链表的节点。
 *
 * <p>{@code left/type/right} 描述当前谓词；{@code prev/next} 串联同一层级的条件。
 * {@code connector} 保存在后一个节点上，表示它与前一个有效节点之间的连接关系。</p>
 */
public class Condition implements Operand {

    protected ConditionType type;
    protected Operand left;
    protected Operand right;
    /** 是否参与最终表达式渲染。 */
    protected boolean effective = true;
    protected Connector connector;
    protected Condition prev;
    protected Condition next;

    public Condition() {
    }

    public Condition(ConditionType type, Operand left, Operand right) {
        this.type = type;
        this.left = left;
        this.right = right;

        if (left instanceof Value) {
            ((Value) left).setCondition(this);
        }
        if (right instanceof Value) {
            ((Value) right).setCondition(this);
        }
    }


    /**
     * 将条件追加到当前链表末尾。
     *
     * @param nextCondition 待追加的条件或分组
     * @param connector 与前一个条件之间的逻辑连接符
     */
    public void connect(Condition nextCondition, Connector connector) {
        if (this.next != null) {
            this.next.connect(nextCondition, connector);
        } else {
            nextCondition.connector = connector;
            this.next = nextCondition;
            nextCondition.prev = this;
        }
    }

    /** 返回当前节点是否应参与表达式渲染。 */
    public boolean checkEffective() {
        return effective;
    }

    /** 查找前一个有效条件，用于判断是否需要输出连接符。 */
    protected Condition getPrevEffectiveCondition() {
        if (prev == null) {
            return null;
        }
        return prev.checkEffective() ? prev : prev.getPrevEffectiveCondition();
    }

    /** 查找后一个有效条件。 */
    protected Condition getNextEffectiveCondition() {
        if (next == null) {
            return null;
        }
        return next.checkEffective() ? next : next.getNextEffectiveCondition();
    }


    @Override
    /**
     * 使用适配器渲染当前节点及其后续条件链。
     *
     * @param adaptor 目标存储对应的表达式适配器
     */
    public String toExpression(ExpressionAdaptor adaptor) {
        StringBuilder expr = new StringBuilder();
        if (checkEffective()) {
            Condition prevEffectiveCondition = getPrevEffectiveCondition();
            if (prevEffectiveCondition != null && this.connector != null) {
                expr.append(adaptor.toConnector(this.connector));
            }
            expr.append(adaptor.toCondition(this));
        }

        if (this.next != null) {
            expr.append(this.next.toExpression(adaptor));
        }

        return expr.toString();
    }


    public ConditionType getType() {
        return type;
    }

    public void setType(ConditionType type) {
        this.type = type;
    }

    public Operand getLeft() {
        return left;
    }

    public void setLeft(Operand left) {
        this.left = left;
    }

    public Operand getRight() {
        return right;
    }

    public void setRight(Operand right) {
        this.right = right;
    }

    public boolean isEffective() {
        return effective;
    }

    public void setEffective(boolean effective) {
        this.effective = effective;
    }

    public Connector getConnector() {
        return connector;
    }

    public void setConnector(Connector connector) {
        this.connector = connector;
    }

    public Condition getPrev() {
        return prev;
    }

    public void setPrev(Condition prev) {
        this.prev = prev;
    }

    public Condition getNext() {
        return next;
    }

    public void setNext(Condition next) {
        this.next = next;
    }

    @Override
    public String toString() {
        return "Condition{" +
            "type=" + type +
            ", left=" + left +
            ", right=" + right +
            ", effective=" + effective +
            ", connector=" + connector +
            ", prev=" + prev +
            ", next=" + next +
            '}';
    }
}
