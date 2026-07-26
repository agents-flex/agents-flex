/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.cassandra;

import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.condition.ConditionType;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Cassandra 条件能力与 DNF 多查询规划的单元测试。 */
public class CassandraConditionPlannerTest {

    @Test
    public void shouldExpandInOrAndBetweenIntoExecutableBranches() {
        SearchWrapper wrapper = new SearchWrapper()
            .in("category", Arrays.asList("AI", "Ops"))
            .between("views", 10, 20)
            .orCriteria(group -> group.eq("status", "featured"));

        List<List<CassandraConditionPlanner.Predicate>> branches =
            CassandraConditionPlanner.plan(wrapper.getCondition());

        assertEquals(3, branches.size());
        assertEquals(3, branches.get(0).size());
        assertEquals("metadata_category", branches.get(0).get(0).getColumn());
        assertEquals(ConditionType.EQ, branches.get(0).get(0).getType());
        assertEquals(ConditionType.GE, branches.get(0).get(1).getType());
        assertEquals(ConditionType.LE, branches.get(0).get(2).getType());
        assertEquals("metadata_status", branches.get(2).get(0).getColumn());
    }

    @Test
    public void shouldPlanSqlStyleExpressionWithCorrectPrecedence() {
        SearchWrapper wrapper = new SearchWrapper()
            .condition("category IN ('AI', 'Java') AND (views >= 10 OR status = 'featured')");

        List<List<CassandraConditionPlanner.Predicate>> branches =
            CassandraConditionPlanner.plan(wrapper.getCondition());

        assertEquals(4, branches.size());
        for (List<CassandraConditionPlanner.Predicate> branch : branches) {
            assertEquals(2, branch.size());
        }
    }

    @Test
    public void shouldNormalizeMetadataPrefixesAndCoreFields() {
        assertEquals("metadata_tenant", CassandraConditionPlanner.normalizeField("tenant"));
        assertEquals("metadata_tenant", CassandraConditionPlanner.normalizeField("metadata.tenant"));
        assertEquals("metadata_tenant", CassandraConditionPlanner.normalizeField("metadataMap.tenant"));
        assertEquals("id", CassandraConditionPlanner.normalizeField("id"));
    }

    @Test
    public void shouldRejectConditionsThatCassandraCannotExecuteCorrectly() {
        assertUnsupported(new SearchWrapper().ne("status", "deleted"), "NE");
        assertUnsupported(new SearchWrapper().nin("category", Arrays.asList("AI")), "NIN");
        assertUnsupported(new SearchWrapper().isNull("optional"), "IS_NULL");
        assertUnsupported(new SearchWrapper().isNotNull("optional"), "IS_NOT_NULL");
        assertUnsupported(new SearchWrapper().not(group -> group.eq("status", "deleted")), "NOT");
    }

    private void assertUnsupported(SearchWrapper wrapper, String expected) {
        try {
            CassandraConditionPlanner.plan(wrapper.getCondition());
            fail("Expected unsupported Cassandra condition: " + expected);
        } catch (IllegalArgumentException exception) {
            assertTrue(exception.getMessage(), exception.getMessage().contains(expected));
            assertTrue(exception.getMessage(), exception.getMessage().contains("Cassandra 5.x"));
        }
    }
}
