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
 * 条件右侧的值操作数。
 *
 * <p>普通比较保存单值，IN/NOT IN 与 BETWEEN 保存 {@code Object[]}。关联的
 * {@link Condition} 用于让适配器根据条件类型选择正确的值格式。</p>
 */
public class Value implements Operand {

    private Condition condition;
    private Object value;

    public Value(Object value) {
        this.value = value;
    }

    /** 创建多值操作数，主要用于 IN/NOT IN 和 BETWEEN。 */
    public Value(Object... values){
        this.value = values;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public Condition getCondition() {
        return condition;
    }

    public void setCondition(Condition condition) {
        this.condition = condition;
    }

    @Override
    /** 委托适配器按所属条件类型渲染当前值。 */
    public String toExpression(ExpressionAdaptor adaptor) {
        if (value instanceof Operand) {
            // 值本身就是一个可渲染操作数（例如字段引用）时，应交给适配器渲染该操作数；
            // 把当前 Value 再传给 toRight 会让适配器回到本方法，形成无限递归。
            return adaptor.toRight((Operand) value);
        }
        return adaptor.toValue(condition, value);
    }
}
