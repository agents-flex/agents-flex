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

import java.util.function.Function;

/**
 * SQL 重写器接口（函数式接口，支持 Lambda）
 * <p>
 * 典型用途：
 * <ul>
 *   <li>自动添加租户隔离条件：WHERE tenant_id = ?</li>
 *   <li>强制添加 LIMIT 防止全表扫描</li>
 *   <li>字段脱敏：SELECT password → SELECT '***' AS password</li>
 *   <li>SQL 方言适配：MySQL → PostgreSQL 语法转换</li>
 *   <li>软删除过滤：自动添加 AND deleted = 0</li>
 * </ul>
 * <b>注意</b>：重写器应保持幂等性，避免多次应用产生副作用。
 *
 * @return 重写后的 SqlContext；如需保持原样，直接返回入参即可
 */
@FunctionalInterface
public interface SqlRewriter {
    SqlContext rewrite(SqlRewriteContext context);

    /**
     * 快捷创建：从 Function 转换
     */
    static SqlRewriter of(Function<SqlRewriteContext, SqlContext> func) {
        return func::apply;
    }
}
