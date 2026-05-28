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

import com.alibaba.fastjson2.JSON;
import com.agentsflex.text2sql.core.SqlExecuteContext;
import com.agentsflex.text2sql.core.SqlInterceptor;
import com.agentsflex.text2sql.core.SqlInvocation;

/**
 * SQL audit interceptor
 *
 * @author Michael Yang
 */
public class SqlAuditInterceptor implements SqlInterceptor {

    @Override
    public Object intercept(SqlInvocation invocation) throws Exception {

        SqlExecuteContext ctx = invocation.getContext();

        long start = System.currentTimeMillis();

        try {

            System.out.println("\n========== SQL BEGIN ==========");
            System.out.println("Tool Name    : " + ctx.getToolName());
            System.out.println("Data Source  : " + ctx.getDataSource().getName());
            System.out.println("Original SQL : " + ctx.getOriginalSql());
            System.out.println("Current SQL  : " + ctx.getSql());
            System.out.println("Parameters   : " + JSON.toJSONString(ctx.getParameters()));

            Object result = invocation.proceed();
            long cost = System.currentTimeMillis() - start;
            System.out.println("Cost(ms)     : " + cost);
            System.out.println("========== SQL SUCCESS ==========\n");

            return result;

        } catch (Exception e) {

            long cost = System.currentTimeMillis() - start;
            System.err.println("\n========== SQL ERROR ==========");
            System.err.println("Error        : " + e.getMessage());
            System.err.println("Cost(ms)     : " + cost);
            System.err.println("========== SQL ERROR ==========\n");

            throw e;
        }
    }
}
