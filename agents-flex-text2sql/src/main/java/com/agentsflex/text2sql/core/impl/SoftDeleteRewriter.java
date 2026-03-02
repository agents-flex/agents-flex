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

/**
 * 内置：软删除过滤重写器（自动添加 AND deleted = 0）
 */
public class SoftDeleteRewriter implements SqlRewriter {
    private final String deleteColumn;
    private final Object deleteValue;

    public SoftDeleteRewriter() {
        this("deleted", 0);
    }

    public SoftDeleteRewriter(String deleteColumn, Object deleteValue) {
        this.deleteColumn = deleteColumn;
        this.deleteValue = deleteValue;
    }

    @Override
    public SqlContext rewrite(SqlRewriteContext context) {
        String originalSql = context.getCurrentSql().getSql();
        List<Object> originalParams = context.getCurrentSql().getParams();
        String lowerSql = originalSql.toLowerCase().trim();

        String appendSql;
        List<Object> newParams = new ArrayList<>(originalParams);

        if (lowerSql.contains(" where ")) {
            appendSql = originalSql + " AND " + deleteColumn + " = ?";
        } else {
            int orderByIdx = lowerSql.lastIndexOf(" order by ");
            int limitIdx = lowerSql.lastIndexOf(" limit ");
            int insertPos = Math.min(
                orderByIdx > 0 ? orderByIdx : originalSql.length(),
                limitIdx > 0 ? limitIdx : originalSql.length()
            );
            appendSql = originalSql.substring(0, insertPos)
                + " WHERE " + deleteColumn + " = ?"
                + originalSql.substring(insertPos);
        }

        newParams.add(deleteValue);
        return context.getCurrentSql().with(appendSql, newParams);
    }
}
