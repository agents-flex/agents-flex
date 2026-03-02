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
package com.agentsflex.text2sql.core.impl;

import com.agentsflex.text2sql.core.SqlContext;
import com.agentsflex.text2sql.core.SqlRewriteContext;
import com.agentsflex.text2sql.core.SqlRewriter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 内置：租户隔离重写器（自动添加 WHERE tenant_id = ?）
 */
public class TenantSqlRewriter implements SqlRewriter {
    private final String tenantColumn;
    private final Function<SqlRewriteContext, Object> callerProvider;

    public TenantSqlRewriter(Function<SqlRewriteContext, Object> callerProvider) {
        this("tenant_id", callerProvider);
    }

    public TenantSqlRewriter(String tenantColumn, Function<SqlRewriteContext, Object> callerProvider) {
        this.tenantColumn = tenantColumn;
        this.callerProvider = callerProvider;
    }

    @Override
    public SqlContext rewrite(SqlRewriteContext context) {
        if (callerProvider == null) {
            return context.getCurrentSql();
        }

        Object object = callerProvider.apply(context);
        if (object == null) {
            return context.getCurrentSql();
        }

        String originalSql = context.getCurrentSql().getSql();
        List<Object> originalParams = context.getCurrentSql().getParams();

        String lowerSql = originalSql.toLowerCase().trim();
        String appendSql;
        List<Object> newParams = new ArrayList<>(originalParams);

        if (lowerSql.contains(" where ")) {
            appendSql = originalSql + " AND " + tenantColumn + " = ?";
        } else {
            int orderByIdx = lowerSql.lastIndexOf(" order by ");
            int limitIdx = lowerSql.lastIndexOf(" limit ");
            int groupByIdx = lowerSql.lastIndexOf(" group by ");
            int havingIdx = lowerSql.lastIndexOf(" having ");

            int insertPos = originalSql.length();
            if (orderByIdx > 0) insertPos = Math.min(insertPos, orderByIdx);
            if (limitIdx > 0) insertPos = Math.min(insertPos, limitIdx);
            if (groupByIdx > 0) insertPos = Math.min(insertPos, groupByIdx);
            if (havingIdx > 0) insertPos = Math.min(insertPos, havingIdx);

            appendSql = originalSql.substring(0, insertPos)
                + " WHERE " + tenantColumn + " = ?"
                + originalSql.substring(insertPos);
        }

        newParams.add(object);

        return context.getCurrentSql().with(appendSql, newParams);
    }
}
