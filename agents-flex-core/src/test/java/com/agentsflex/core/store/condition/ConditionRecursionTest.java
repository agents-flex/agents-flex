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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 条件树自引用结构上的递归边界：toString 与操作数右值渲染都必须能正常终止。
 */
public class ConditionRecursionTest {

    @Test
    public void shouldPrintChainedConditionWithoutRecursingThroughNeighbours() {
        Condition first = ConditionExpressionParser.parse("a = 1 AND b = 2");
        Condition second = first.getNext();

        String firstText = first.toString();
        String secondText = second.toString();

        // 两个节点互相持有 prev/next，打印邻居会在这两个 toString 之间无限递归
        assertTrue(firstText.contains("hasPrev=false"));
        assertTrue(firstText.contains("hasNext=true"));
        assertTrue(secondText.contains("hasPrev=true"));
        assertTrue(secondText.contains("hasNext=false"));
    }

    @Test
    public void shouldPrintGroupAndNegationWithoutRecursingThroughNeighbours() {
        Condition grouped = ConditionExpressionParser.parse("(a = 1 OR b = 2) AND c = 3");
        Condition negated = ConditionExpressionParser.parse("a NOT IN (1, 2)");

        assertTrue(grouped.toString().contains("hasNext=true"));
        assertTrue(negated.toString().contains("hasPrev=false"));
        assertTrue(negated.toString().contains("hasNext=false"));
    }

    @Test
    public void shouldRenderOperandValueAsFieldReference() {
        Condition condition = new Condition(ConditionType.EQ, new Key("a"), new Value(new Key("b")));

        assertEquals("a = b", condition.toExpression(ExpressionAdaptor.DEFAULT));
    }
}
