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

/**
 * The row cap has to be added to the statement the caller actually runs. The rewritten SQL is
 * executed against an in-memory database, so a cap that was not added shows up as more rows than
 * {@code maxLimit} instead of a different string, and a cap spliced in the wrong place shows up as a
 * syntax error.
 */
public class LimitSqlInterceptorTest {

    /**
     * Small enough to stay below the number of rows in the table, so a missing cap is visible.
     */
    private static final int MAX_LIMIT = 2;

    private static final int TOTAL_ROWS = 5;

    private JdbcDataSource dataSource;

    @Before
    public void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:limit_sql_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE big (id INT, status VARCHAR(16), limit_amount INT)");
            statement.execute("INSERT INTO big VALUES "
                + "(1, 'new', 10), (2, 'new', 20), (3, 'new', 30), (4, 'old', 40), (5, 'unlimited', 50)");
        }
    }

    @After
    public void tearDown() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS big");
        }
    }

    @Test
    public void addsLimitWhenStatementHasNone() throws Exception {
        assertEquals(MAX_LIMIT, query("SELECT id FROM big").size());
    }

    /**
     * "unlimited" contains "LIMIT"; the old substring test saw a cap that was not there and left the
     * statement unbounded.
     */
    @Test
    public void addsLimitWhenStringLiteralContainsLimitKeyword() throws Exception {
        assertEquals(MAX_LIMIT, query("SELECT id FROM big WHERE status <> 'unlimited'").size());
    }

    /**
     * A column named {@code limit_amount} is not a LIMIT clause either.
     */
    @Test
    public void addsLimitWhenColumnNameContainsLimitKeyword() throws Exception {
        assertEquals(MAX_LIMIT, query("SELECT id, limit_amount FROM big").size());
    }

    /**
     * A terminating semicolon has to stay at the end, the cap goes in front of it.
     */
    @Test
    public void keepsLimitBeforeTerminatingSemicolon() throws Exception {
        assertEquals(MAX_LIMIT, query("SELECT id FROM big;").size());
    }

    /**
     * An existing cap is left alone, including one below {@code maxLimit}.
     */
    @Test
    public void leavesExistingLimitAlone() throws Exception {
        assertEquals(1, query("SELECT id FROM big LIMIT 1").size());
    }

    /**
     * A cap on an inner query does not bound the outer statement.
     */
    @Test
    public void ignoresLimitInsideSubquery() throws Exception {
        assertEquals(MAX_LIMIT, query("SELECT id FROM (SELECT id FROM big LIMIT 3) t").size());
    }

    /**
     * A locking clause has to stay last, so the cap goes in front of it.
     */
    @Test
    public void keepsLimitBeforeLockingClause() throws Exception {
        assertEquals(MAX_LIMIT, query("SELECT id FROM big FOR UPDATE").size());
    }

    private List<Map<String, Object>> query(String sql) throws Exception {
        SqlExecuteContext context = new SqlExecuteContext();
        context.setOriginalSql(sql);
        context.setSql(sql);
        context.setParameters(new ArrayList<>());

        List<SqlInterceptor> interceptors = Collections.<SqlInterceptor>singletonList(
            new LimitSqlInterceptor(MAX_LIMIT));
        Object result = new SqlInvocation(interceptors, context,
            ctx -> JdbcQueryUtil.query(dataSource, ctx.getSql(), ctx.getParameters())).proceed();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result;
        return rows;
    }
}
