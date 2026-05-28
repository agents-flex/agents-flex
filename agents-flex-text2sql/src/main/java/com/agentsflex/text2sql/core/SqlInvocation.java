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

import java.util.List;

/**
 * Default SQL Invocation
 *
 * @author Michael Yang
 */
public class SqlInvocation {

    private final List<SqlInterceptor> interceptors;
    private final SqlExecuteContext context;
    private final SqlExecutor executor;

    /**
     * Current interceptor index
     */
    private int index = -1;

    public SqlInvocation(List<SqlInterceptor> interceptors, SqlExecuteContext context, SqlExecutor executor) {
        this.interceptors = interceptors;
        this.context = context;
        this.executor = executor;
    }

    public SqlExecuteContext getContext() {
        return context;
    }

    public Object proceed() throws Exception {
        index++;

        // Continue interceptor chain
        if (index < interceptors.size()) {
            SqlInterceptor interceptor = interceptors.get(index);
            return interceptor.intercept(this);
        }

        // Final JDBC execution
        return executor.execute(context);
    }
}
