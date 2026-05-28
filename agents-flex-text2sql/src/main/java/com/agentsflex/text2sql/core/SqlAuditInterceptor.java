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
package com.agentsflex.text2sql.core;

import com.alibaba.fastjson2.JSON;

/**
 * Default SQL Audit Interceptor
 *
 * @author Michael Yang
 */
public class SqlAuditInterceptor implements SqlInterceptor {

    @Override
    public void beforeQuery(SqlExecuteContext context) {

        System.out.println("\n================ SQL EXECUTE BEGIN ================");
        System.out.println("Tool Name      : " + context.getToolName());
        System.out.println("Data Source    : " + context.getDataSource().getName());
        System.out.println("Original SQL   : " + context.getOriginalSql());
        System.out.println("Rewritten SQL  : " + context.getRewrittenSql());
        System.out.println("Parameters     : " + JSON.toJSONString(context.getParameters()));
        System.out.println("Request ID     : " + context.getRequestId());
        System.out.println("===================================================\n");
    }

    @Override
    public void afterQuery(SqlExecuteContext context, Object result) {

        long cost = System.currentTimeMillis() - context.getStartTime();

        System.out.println("\n================ SQL EXECUTE SUCCESS ================");
        System.out.println("Tool Name      : " + context.getToolName());
        System.out.println("Cost(ms)       : " + cost);
        System.out.println("=====================================================\n");
    }

    @Override
    public void onError(SqlExecuteContext context, Exception e) {

        long cost = System.currentTimeMillis() - context.getStartTime();

        System.err.println("\n================ SQL EXECUTE ERROR =================");
        System.err.println("Tool Name      : " + context.getToolName());
        System.err.println("Cost(ms)       : " + cost);
        System.err.println("Error Message  : " + e.getMessage());
        System.err.println("====================================================\n");
    }
}
