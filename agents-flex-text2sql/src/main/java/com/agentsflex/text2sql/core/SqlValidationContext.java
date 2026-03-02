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

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 验证上下文（包含额外元数据）
 */
public class SqlValidationContext {
    private final String dataSourceName;
    private final String originalSql;
    private final List<Object> originalParams;
    private final Object caller; // 可选：调用方标识（如 userId）

    public SqlValidationContext(String dataSourceName, String sql,
                                List<Object> params, Object caller) {
        this.dataSourceName = dataSourceName;
        this.originalSql = sql;
        this.originalParams = params != null ? new ArrayList<>(params) : new ArrayList<>();
        this.caller = caller;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

    public String getOriginalSql() {
        return originalSql;
    }

    public List<Object> getOriginalParams() {
        return new ArrayList<>(originalParams);
    }

    public Object getCaller() {
        return caller;
    }
}
