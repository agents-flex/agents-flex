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
        String sql = ctx.getSql().toUpperCase();
        if (!sql.contains("LIMIT")) {
            ctx.setSql(ctx.getSql() + " LIMIT " + maxLimit);
        }

        return invocation.proceed();
    }
}
