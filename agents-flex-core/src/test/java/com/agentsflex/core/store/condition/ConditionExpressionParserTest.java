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

import java.math.BigDecimal;

import static org.junit.Assert.*;

public class ConditionExpressionParserTest {

    @Test
    public void shouldParseComparisonAndGroupedNotIn() {
        Condition condition = ConditionExpressionParser.parse(
            "xxx > 10 and (ccc not in ('a', 'b'))");

        assertCondition(condition, ConditionType.GT, "xxx", 10L);
        assertTrue(condition.getNext() instanceof Group);
        assertEquals(Connector.AND, condition.getNext().getConnector());
        Condition child = ((Group) condition.getNext()).getChildCondition();
        assertCondition(child, ConditionType.NIN, "ccc", new Object[]{"a", "b"});
    }

    @Test
    public void shouldHonorAndBeforeOrAndParentheses() {
        Condition condition = ConditionExpressionParser.parse("a = 1 OR b = 2 AND c = 3");

        assertCondition(condition, ConditionType.EQ, "a", 1L);
        assertEquals(Connector.OR, condition.getNext().getConnector());
        assertCondition(condition.getNext(), ConditionType.EQ, "b", 2L);
        assertEquals(Connector.AND, condition.getNext().getNext().getConnector());
        assertCondition(condition.getNext().getNext(), ConditionType.EQ, "c", 3L);

        Condition grouped = ConditionExpressionParser.parse("(a = 1 OR b = 2) AND c = 3");
        assertTrue(grouped instanceof Group);
        assertEquals(Connector.OR, ((Group) grouped).getChildCondition().getNext().getConnector());
        assertEquals(Connector.AND, grouped.getNext().getConnector());
    }

    @Test
    public void shouldParseQuotedFieldsEscapesAndTypedLiterals() {
        Condition condition = ConditionExpressionParser.parse(
            "`user-name` = 'O''Reilly' AND path = \"a\\\\b\" "
                + "AND ratio >= -1.25e2 AND active = TRUE AND deleted IS NOT NULL");

        assertCondition(condition, ConditionType.EQ, "user-name", "O'Reilly");
        assertCondition(condition.getNext(), ConditionType.EQ, "path", "a\\b");
        assertCondition(condition.getNext().getNext(), ConditionType.GE, "ratio", new BigDecimal("-1.25e2"));
        assertCondition(condition.getNext().getNext().getNext(), ConditionType.EQ, "active", true);
        assertCondition(condition.getNext().getNext().getNext().getNext(),
            ConditionType.NE, "deleted", null);
    }

    @Test
    public void shouldParseSqlOperatorAliasesAndNotBetween() {
        Condition condition = ConditionExpressionParser.parse(
            "status <> 'deleted' AND version == 2 AND score NOT BETWEEN 0.2 AND 0.8");

        assertCondition(condition, ConditionType.NE, "status", "deleted");
        assertCondition(condition.getNext(), ConditionType.EQ, "version", 2L);
        assertTrue(condition.getNext().getNext() instanceof Not);
        Condition between = ((Not) condition.getNext().getNext()).getChildCondition();
        assertCondition(between, ConditionType.BETWEEN, "score",
            new Object[]{new BigDecimal("0.2"), new BigDecimal("0.8")});
    }

    @Test
    public void shouldParseEveryComparisonOperator() {
        assertCondition(ConditionExpressionParser.parse("a = 1"), ConditionType.EQ, "a", 1L);
        assertCondition(ConditionExpressionParser.parse("a == 1"), ConditionType.EQ, "a", 1L);
        assertCondition(ConditionExpressionParser.parse("a != 1"), ConditionType.NE, "a", 1L);
        assertCondition(ConditionExpressionParser.parse("a <> 1"), ConditionType.NE, "a", 1L);
        assertCondition(ConditionExpressionParser.parse("a > 1"), ConditionType.GT, "a", 1L);
        assertCondition(ConditionExpressionParser.parse("a >= 1"), ConditionType.GE, "a", 1L);
        assertCondition(ConditionExpressionParser.parse("a < 1"), ConditionType.LT, "a", 1L);
        assertCondition(ConditionExpressionParser.parse("a <= 1"), ConditionType.LE, "a", 1L);
    }

    @Test
    public void shouldTreatKeywordsCaseInsensitivelyAndIgnoreWhitespace() {
        Condition condition = ConditionExpressionParser.parse(
            "\n\tactive = tRuE\r AnD category nOt iN ( 'x' , \"y\" ) ");

        assertCondition(condition, ConditionType.EQ, "active", true);
        assertEquals(Connector.AND, condition.getNext().getConnector());
        assertCondition(condition.getNext(), ConditionType.NIN, "category", new Object[]{"x", "y"});
    }

    @Test
    public void shouldParseIntegerDecimalAndScientificNumbers() {
        Condition condition = ConditionExpressionParser.parse(
            "zero = 0 AND positive = +12 AND negative = -12 "
                + "AND decimal = 1.25 AND exponent = -1.25e2 AND upperExponent = 2E+3");

        assertCondition(condition, ConditionType.EQ, "zero", 0L);
        assertCondition(condition.getNext(), ConditionType.EQ, "positive", 12L);
        assertCondition(condition.getNext().getNext(), ConditionType.EQ, "negative", -12L);
        assertCondition(condition.getNext().getNext().getNext(),
            ConditionType.EQ, "decimal", new BigDecimal("1.25"));
        assertCondition(condition.getNext().getNext().getNext().getNext(),
            ConditionType.EQ, "exponent", new BigDecimal("-1.25e2"));
        assertCondition(condition.getNext().getNext().getNext().getNext().getNext(),
            ConditionType.EQ, "upperExponent", new BigDecimal("2E+3"));
    }

    @Test
    public void shouldParseEmptyDoubledAndBackslashEscapedStrings() {
        Condition condition = ConditionExpressionParser.parse(
            "empty = '' AND single = 'it''s' AND double = \"say \"\"hi\"\"\" "
                + "AND escapes = '\\n\\r\\t\\b\\f\\\\\\\'\\\"'");

        assertCondition(condition, ConditionType.EQ, "empty", "");
        assertCondition(condition.getNext(), ConditionType.EQ, "single", "it's");
        assertCondition(condition.getNext().getNext(), ConditionType.EQ, "double", "say \"hi\"");
        assertCondition(condition.getNext().getNext().getNext(), ConditionType.EQ, "escapes",
            "\n\r\t\b\f\\'\"");
    }

    @Test
    public void shouldParseSupportedFieldNames() {
        Condition condition = ConditionExpressionParser.parse(
            "metadata.category = 1 AND _private = 2 AND $system = 3 "
                + "AND field-name = 4 AND 中文字段 = 5 AND `a``b` = 6");

        String[] fields = {"metadata.category", "_private", "$system", "field-name", "中文字段", "a`b"};
        Condition current = condition;
        for (int index = 0; index < fields.length; index++) {
            assertCondition(current, ConditionType.EQ, fields[index], (long) index + 1);
            current = current.getNext();
        }
        assertNull(current);
    }

    @Test
    public void shouldParseInAndBetweenWithTypedValues() {
        Condition condition = ConditionExpressionParser.parse(
            "kind IN ('a') AND mixed IN (1, 2.5, true, 'x') "
                + "AND age BETWEEN 18 AND 65 AND code BETWEEN 'A' AND 'Z'");

        assertCondition(condition, ConditionType.IN, "kind", new Object[]{"a"});
        assertCondition(condition.getNext(), ConditionType.IN, "mixed",
            new Object[]{1L, new BigDecimal("2.5"), true, "x"});
        assertCondition(condition.getNext().getNext(), ConditionType.BETWEEN, "age",
            new Object[]{18L, 65L});
        assertCondition(condition.getNext().getNext().getNext(), ConditionType.BETWEEN, "code",
            new Object[]{"A", "Z"});
    }

    @Test
    public void shouldParseAllSupportedNullPredicates() {
        Condition condition = ConditionExpressionParser.parse(
            "a = NULL AND b != null AND c IS NULL AND d is not null");

        assertCondition(condition, ConditionType.EQ, "a", null);
        assertCondition(condition.getNext(), ConditionType.NE, "b", null);
        assertCondition(condition.getNext().getNext(), ConditionType.EQ, "c", null);
        assertCondition(condition.getNext().getNext().getNext(), ConditionType.NE, "d", null);
    }

    @Test
    public void shouldParseNestedAndRepeatedNot() {
        Condition condition = ConditionExpressionParser.parse(
            "NOT NOT a = 1 OR b = 2 AND NOT (c = 3 OR d = 4)");

        assertTrue(condition instanceof Not);
        assertTrue(((Not) condition).getChildCondition() instanceof Not);
        assertCondition(((Not) ((Not) condition).getChildCondition()).getChildCondition(),
            ConditionType.EQ, "a", 1L);
        assertEquals(Connector.OR, condition.getNext().getConnector());
        assertCondition(condition.getNext(), ConditionType.EQ, "b", 2L);
        assertEquals(Connector.AND, condition.getNext().getNext().getConnector());
        assertTrue(condition.getNext().getNext() instanceof Not);
        assertTrue(((Not) condition.getNext().getNext()).getChildCondition() instanceof Group);
    }

    @Test
    public void shouldPreserveComplexGroupingAndConnectorOrder() {
        Condition condition = ConditionExpressionParser.parse(
            "((a = 1 OR b = 2) AND (c = 3 OR NOT (d = 4))) OR e = 5 AND f = 6");

        assertTrue(condition instanceof Group);
        Condition outerChild = ((Group) condition).getChildCondition();
        assertTrue(outerChild instanceof Group);
        assertEquals(Connector.AND, outerChild.getNext().getConnector());
        assertTrue(outerChild.getNext() instanceof Group);
        Condition rightGroup = ((Group) outerChild.getNext()).getChildCondition();
        assertCondition(rightGroup, ConditionType.EQ, "c", 3L);
        assertEquals(Connector.OR, rightGroup.getNext().getConnector());
        assertTrue(rightGroup.getNext() instanceof Not);
        assertEquals(Connector.OR, condition.getNext().getConnector());
        assertCondition(condition.getNext(), ConditionType.EQ, "e", 5L);
        assertEquals(Connector.AND, condition.getNext().getNext().getConnector());
        assertCondition(condition.getNext().getNext(), ConditionType.EQ, "f", 6L);
    }

    @Test
    public void shouldReturnIndependentTreesForSeparateParses() {
        Condition first = ConditionExpressionParser.parse("a = 1 AND b = 2");
        Condition second = ConditionExpressionParser.parse("x = 3");

        first.getNext().setRight(new Value(99L));
        assertCondition(first.getNext(), ConditionType.EQ, "b", 99L);
        assertCondition(second, ConditionType.EQ, "x", 3L);
        assertNull(second.getNext());
    }

    @Test
    public void shouldParsePrefixNotAndNullEquality() {
        Condition condition = ConditionExpressionParser.parse(
            "NOT (enabled = false OR optional = NULL)");

        assertTrue(condition instanceof Not);
        Condition group = ((Not) condition).getChildCondition();
        assertTrue(group instanceof Group);
        Condition child = ((Group) group).getChildCondition();
        assertCondition(child, ConditionType.EQ, "enabled", false);
        assertCondition(child.getNext(), ConditionType.EQ, "optional", null);
    }

    @Test
    public void shouldReportPreciseSyntaxErrors() {
        assertInvalid("", 0, "blank");
        assertInvalid("   \t", 4, "blank");
        assertInvalid("!", 0, "Expected '='");
        assertInvalid("@ = 1", 0, "Unexpected character");
        assertInvalid("= 1", 0, "Expected a field");
        assertInvalid("name", 4, "Expected a comparison operator");
        assertInvalid("name = alice", 7, "Expected a quoted string");
        assertInvalid("name = 'bad", 7, "Unterminated string");
        assertInvalid("name = 'bad\\", 11, "Unterminated escape");
        assertInvalid("`` = 1", 0, "cannot be empty");
        assertInvalid("`name = 1", 0, "Unterminated quoted field");
        assertInvalid("a = 1)", 5, "Unexpected token");
        assertInvalid("category IN ()", 13, "at least one");
        assertInvalid("category IN ('a', NULL)", 18, "NULL is not supported");
        assertInvalid("category IN 'a'", 12, "Expected '('");
        assertInvalid("category IN ('a' 'b')", 17, "Expected ')'");
        assertInvalid("category IN ('a',)", 17, "Expected a quoted string");
        assertInvalid("views BETWEEN 1 2", 16, "Expected AND");
        assertInvalid("views BETWEEN NULL AND 2", 14, "cannot be NULL");
        assertInvalid("views BETWEEN 1 AND NULL", 20, "cannot be NULL");
        assertInvalid("views > NULL", 8, "NULL is only supported");
        assertInvalid("a IS true", 5, "Expected NULL");
        assertInvalid("a NOT NULL", 6, "Expected IN or BETWEEN");
        assertInvalid("(a = 1", 6, "Expected ')'");
        assertInvalid("()", 1, "Expected a field");
        assertInvalid("a = 1. ", 6, "Expected digits after decimal");
        assertInvalid("a = 1e", 6, "Expected exponent digits");
        assertInvalid("a = 1e+", 7, "Expected exponent digits");
        assertInvalid("a = 9223372036854775808", 4, "out-of-range");
        assertInvalid("a = .5", 4, "Unexpected character");
        assertInvalid("a = 1 b = 2", 6, "Unexpected token");
        assertInvalid("name = 'bad\\x'", 12, "Unsupported escape");
    }

    @Test
    public void shouldRejectNullExpression() {
        try {
            ConditionExpressionParser.parse(null);
            fail("Expected null expression to be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("Condition expression cannot be null", expected.getMessage());
        }
    }

    private void assertCondition(Condition condition, ConditionType type, String field, Object value) {
        assertEquals(type, condition.getType());
        assertEquals(field, ((Key) condition.getLeft()).getKey());
        Object actual = ((Value) condition.getRight()).getValue();
        if (value instanceof Object[]) {
            assertArrayEquals((Object[]) value, (Object[]) actual);
        } else {
            assertEquals(value, actual);
        }
    }

    private void assertInvalid(String expression, int position, String message) {
        try {
            ConditionExpressionParser.parse(expression);
            fail("Expected invalid expression: " + expression);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("position " + position));
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }
}
