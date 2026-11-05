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
package com.agentsflex.text2sql.interceptor;

import com.agentsflex.text2sql.core.SqlExecuteContext;
import com.agentsflex.text2sql.core.SqlInterceptor;
import com.agentsflex.text2sql.core.SqlInvocation;
import com.agentsflex.text2sql.util.JdbcQueryUtil;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The tenant predicate has to end up inside the {@code WHERE} clause of the statement, whichever
 * shape the statement has: the rewritten SQL is executed against an in-memory database, so a
 * predicate pasted after {@code ORDER BY} / {@code LIMIT} / a terminating semicolon shows up as a
 * syntax error instead of a silently wrong result.
 */
public class TenantSqlInterceptorTest {

    private static final long TENANT = 1L;

    private JdbcDataSource dataSource;

    @Before
    public void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:tenant_sql_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id INT, tenant_id BIGINT, amount INT, note VARCHAR(32))");
            statement.execute("INSERT INTO orders VALUES "
                + "(1, 1, 10, 'own'), (2, 1, 20, 'own'), (3, 2, 30, 'other')");
        }
    }

    @After
    public void tearDown() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS orders");
        }
    }

    /**
     * A statement without a WHERE clause needs one; appending " AND ..." produced invalid SQL.
     */
    @Test
    public void addsWhereClauseWhenStatementHasNone() throws Exception {
        List<Map<String, Object>> rows = query("SELECT id, tenant_id FROM orders");
        assertEquals(2, rows.size());
        assertOnlyOwnTenant(rows);
    }

    /**
     * The shape reported in issue #39: the predicate used to land after ORDER BY and LIMIT.
     */
    @Test
    public void keepsPredicateBeforeOrderByAndLimit() throws Exception {
        List<Map<String, Object>> rows = query(
            "SELECT id, tenant_id FROM orders ORDER BY id DESC LIMIT 2");
        assertEquals(2, rows.size());
        assertOnlyOwnTenant(rows);
        assertEquals(2, ((Number) rows.get(0).get("ID")).intValue());
    }

    /**
     * GROUP BY and HAVING have to stay behind the tenant condition as well.
     */
    @Test
    public void keepsPredicateBeforeGroupByAndHaving() throws Exception {
        List<Map<String, Object>> rows = query(
            "SELECT tenant_id, SUM(amount) AS total FROM orders GROUP BY tenant_id HAVING SUM(amount) > 0");
        assertEquals(1, rows.size());
        assertOnlyOwnTenant(rows);
        assertEquals(30, ((Number) rows.get(0).get("TOTAL")).intValue());
    }

    /**
     * A terminating semicolon has to stay at the end of the statement.
     */
    @Test
    public void keepsTerminatingSemicolonLast() throws Exception {
        List<Map<String, Object>> rows = query("SELECT id, tenant_id FROM orders;");
        assertEquals(2, rows.size());
        assertOnlyOwnTenant(rows);
    }

    /**
     * An existing condition is extended with AND instead of a second WHERE.
     */
    @Test
    public void extendsExistingWhereCondition() throws Exception {
        List<Map<String, Object>> rows = query(
            "SELECT id, tenant_id FROM orders WHERE amount >= 10 ORDER BY id");
        assertEquals(2, rows.size());
        assertOnlyOwnTenant(rows);
    }

    /**
     * A tail clause inside a string literal is not a clause, so the predicate must not be spliced
     * into the literal.
     */
    @Test
    public void ignoresTailClauseKeywordsInsideStringLiterals() throws Exception {
        List<Map<String, Object>> rows = query(
            "SELECT id, tenant_id FROM orders WHERE note = 'order by' OR note = 'own' ORDER BY id");
        assertEquals(2, rows.size());
        assertOnlyOwnTenant(rows);
    }

    /**
     * Without a tenant attribute the statement is passed through unchanged.
     */
    @Test
    public void leavesStatementUntouchedWithoutTenant() throws Exception {
        List<Map<String, Object>> rows = query("SELECT id, tenant_id FROM orders", null);
        assertEquals(3, rows.size());
    }

    private List<Map<String, Object>> query(String sql) throws Exception {
        return query(sql, TENANT);
    }

    private List<Map<String, Object>> query(String sql, Long tenantId) throws Exception {
        SqlExecuteContext context = new SqlExecuteContext();
        context.setOriginalSql(sql);
        context.setSql(sql);
        context.setParameters(new ArrayList<>());
        if (tenantId != null) {
            context.addAttribute("tenantId", tenantId);
        }

        List<SqlInterceptor> interceptors = Collections.<SqlInterceptor>singletonList(new TenantSqlInterceptor());
        Object result = new SqlInvocation(interceptors, context,
            ctx -> JdbcQueryUtil.query(dataSource, ctx.getSql(), ctx.getParameters())).proceed();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result;
        return rows;
    }

    private static void assertOnlyOwnTenant(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            assertTrue("row belongs to tenant " + row.get("TENANT_ID"),
                TENANT == ((Number) row.get("TENANT_ID")).longValue());
        }
    }
}
