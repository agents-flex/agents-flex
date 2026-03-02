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

/**
 * 内置：强制 LIMIT 限制（防止全表扫描）
 */
public class LimitEnforcerRewriter implements SqlRewriter {
    private final int maxLimit;

    public LimitEnforcerRewriter(int maxLimit) {
        this.maxLimit = maxLimit;
    }

    @Override
    public SqlContext rewrite(SqlRewriteContext context) {
        String sql = context.getCurrentSql().getSql();
        String lower = sql.toLowerCase();

        if (lower.contains(" limit ")) {
            return context.getCurrentSql();
        }

        return context.getCurrentSql().with(sql + " LIMIT " + maxLimit,
            context.getCurrentSql().getParams());
    }
}
