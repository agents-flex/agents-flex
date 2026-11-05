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

/**
 * Multi-tenant SQL interceptor
 *
 * <p>The tenant predicate is inserted at a clause boundary rather than appended to the end of the
 * statement. A statement that does not end inside a {@code WHERE} condition needs a new
 * {@code WHERE}, and a statement that ends with {@code GROUP BY} / {@code HAVING} /
 * {@code ORDER BY} / {@code LIMIT} must keep those clauses last, otherwise the rewritten statement
 * is no longer valid SQL.</p>
 *
 * @author Michael Yang
 */
public class TenantSqlInterceptor implements SqlInterceptor {

    /**
     * Clauses that have to stay at the end of the statement, so the tenant predicate is inserted
     * in front of the first of them.
     */
    private static final String[] TAIL_CLAUSES = {
        "group by", "having", "order by", "limit", "for update", "for share"
    };

    @Override
    public Object intercept(SqlInvocation invocation) throws Exception {

        SqlExecuteContext ctx = invocation.getContext();
        Long tenantId = (Long) ctx.getAttribute("tenantId");
        if (tenantId != null) {
            ctx.setSql(applyTenantFilter(ctx.getSql(), tenantId));
        }

        return invocation.proceed();
    }

    /**
     * Add the tenant predicate to a statement, keeping the terminating semicolon and any trailing
     * whitespace where they are.
     */
    static String applyTenantFilter(String sql, long tenantId) {
        if (sql == null) {
            return null;
        }

        int bodyEnd = sql.length();
        while (bodyEnd > 0 && Character.isWhitespace(sql.charAt(bodyEnd - 1))) {
            bodyEnd--;
        }
        if (bodyEnd > 0 && sql.charAt(bodyEnd - 1) == ';') {
            bodyEnd--;
        }
        while (bodyEnd > 0 && Character.isWhitespace(sql.charAt(bodyEnd - 1))) {
            bodyEnd--;
        }

        String body = sql.substring(0, bodyEnd);
        String suffix = sql.substring(bodyEnd);
        if (body.isEmpty()) {
            return sql;
        }

        int insertPos = tenantFilterPosition(body);
        String predicate = findKeyword(body, "where", insertPos) >= 0
            ? " AND tenant_id = " + tenantId
            : " WHERE tenant_id = " + tenantId;

        return body.substring(0, insertPos) + predicate + body.substring(insertPos) + suffix;
    }

    /**
     * Position of the first tail clause, or the end of the statement when it has none.
     */
    private static int tenantFilterPosition(String body) {
        int position = body.length();
        for (String clause : TAIL_CLAUSES) {
            int index = findKeyword(body, clause, position);
            if (index >= 0) {
                position = index;
            }
        }
        return position;
    }

    /**
     * First occurrence of a keyword before {@code limit} that sits outside string literals and
     * parentheses, so the clause of an inner query is not mistaken for the clause of the statement.
     *
     * @return index of the keyword, or {@code -1} when it is not present
     */
    private static int findKeyword(String sql, String keyword, int limit) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < limit; i++) {
            char c = sql.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < limit && sql.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
            } else if (depth == 0 && matchesKeywordAt(sql, i, keyword, limit)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Case-insensitive word-boundary match, tolerating any run of whitespace between the words of a
     * multi-word keyword such as {@code order by}.
     */
    private static boolean matchesKeywordAt(String sql, int start, String keyword, int limit) {
        if (start > 0 && !isBoundary(sql.charAt(start - 1))) {
            return false;
        }
        int i = start;
        for (int k = 0; k < keyword.length(); k++) {
            if (i >= limit) {
                return false;
            }
            if (keyword.charAt(k) == ' ') {
                if (!Character.isWhitespace(sql.charAt(i))) {
                    return false;
                }
                while (i < limit && Character.isWhitespace(sql.charAt(i))) {
                    i++;
                }
            } else {
                if (Character.toLowerCase(sql.charAt(i)) != keyword.charAt(k)) {
                    return false;
                }
                i++;
            }
        }
        return i >= limit || isBoundary(sql.charAt(i));
    }

    private static boolean isBoundary(char c) {
        return !Character.isLetterOrDigit(c) && c != '_';
    }
}
