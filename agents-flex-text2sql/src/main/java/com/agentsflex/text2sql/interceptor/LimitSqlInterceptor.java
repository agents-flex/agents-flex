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
 * LIMIT protection interceptor
 *
 * <p>A {@code LIMIT} is only recognised when the keyword stands on its own at the top level of the
 * statement. A substring match reports one for {@code 'unlimited'} or a column named
 * {@code limit_amount}, and then no row cap is added at all — the statement this interceptor exists
 * to bound silently runs unbounded. A {@code LIMIT} of an inner query must not count either, since
 * the outer statement is the one that can return a whole table.</p>
 *
 * @author Michael Yang
 */
public class LimitSqlInterceptor implements SqlInterceptor {

    private final int maxLimit;

    public LimitSqlInterceptor(int maxLimit) {
        this.maxLimit = maxLimit;
    }

    @Override
    public Object intercept(SqlInvocation invocation) throws Exception {

        SqlExecuteContext ctx = invocation.getContext();
        ctx.setSql(applyLimit(ctx.getSql(), maxLimit));

        return invocation.proceed();
    }

    /**
     * Add the row cap when the statement has no top level {@code LIMIT}, keeping a terminating
     * semicolon and any trailing whitespace where they are.
     */
    static String applyLimit(String sql, int maxLimit) {
        if (sql == null) {
            return null;
        }

        int bodyEnd = trimWhitespace(sql, sql.length());
        if (bodyEnd > 0 && sql.charAt(bodyEnd - 1) == ';') {
            bodyEnd = trimWhitespace(sql, bodyEnd - 1);
        }

        String body = sql.substring(0, bodyEnd);
        String suffix = sql.substring(bodyEnd);
        if (body.isEmpty()) {
            return sql;
        }
        if (findKeyword(body, "limit", body.length()) >= 0) {
            return sql;
        }

        int insertPos = lockClausePosition(body);
        return body.substring(0, insertPos) + " LIMIT " + maxLimit + body.substring(insertPos) + suffix;
    }

    /**
     * Position of a trailing {@code FOR UPDATE} / {@code FOR SHARE}, or the end of the statement.
     * The row cap has to stay in front of a locking clause.
     */
    private static int lockClausePosition(String body) {
        int position = body.length();
        for (String clause : new String[]{"for update", "for share"}) {
            int index = findKeyword(body, clause, position);
            if (index >= 0) {
                position = index;
            }
        }
        return position;
    }

    private static int trimWhitespace(String sql, int end) {
        while (end > 0 && Character.isWhitespace(sql.charAt(end - 1))) {
            end--;
        }
        return end;
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
     * multi-word keyword such as {@code for update}.
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
