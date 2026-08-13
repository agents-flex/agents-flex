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
package com.agentsflex.asynctask.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 创建 JDBC Store 所需的任务表与调度索引。
 *
 * <p>完整任务对象保存在 payload 中；同时投影状态、时间、优先级和租约字段，
 * 使数据库能够筛选候选任务并通过条件更新完成原子领取。</p>
 */
public final class JdbcAsyncTaskStoreSchema {
    private final JdbcAsyncTaskStoreConfig config;

    JdbcAsyncTaskStoreSchema(JdbcAsyncTaskStoreConfig config) {
        this.config = config;
    }

    /**
     * 幂等创建表和索引，适合应用启动或迁移测试使用。
     */
    public void createIfNotExists() {
        String table = config.getTablePrefix() + "tasks";
        // 单表同时保存完整快照和调度投影，生产环境也可由迁移工具预建相同结构。
        String create = "CREATE TABLE IF NOT EXISTS " + table + " (task_id VARCHAR(191) PRIMARY KEY,"
            + "version BIGINT NOT NULL,status VARCHAR(32) NOT NULL,priority INTEGER NOT NULL,"
            + "scheduled_submit_at BIGINT NOT NULL,next_query_at BIGINT NOT NULL,created_at BIGINT NOT NULL,"
            + "lease_owner VARCHAR(191),lease_id VARCHAR(191),lease_until BIGINT NOT NULL,"
            + "cancellation_requested BOOLEAN NOT NULL,payload " + config.getBinaryColumnType() + " NOT NULL)";
        try (Connection connection = config.getDataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(create);
            createIndex(statement, config.getTablePrefix() + "submit_idx", table,
                "status,scheduled_submit_at,priority,lease_until");
            createIndex(statement, config.getTablePrefix() + "query_idx", table,
                "status,next_query_at,lease_until");
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to create async task schema", error);
        }
    }

    private void createIndex(Statement statement, String name, String table, String columns) throws SQLException {
        try {
            statement.execute("CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + columns + ")");
        } catch (SQLException unsupported) {
            // 某些数据库不支持索引的 IF NOT EXISTS；索引已存在时可由迁移工具统一处理。
            if (!isAlreadyExists(unsupported)) throw unsupported;
        }
    }

    private boolean isAlreadyExists(SQLException error) {
        String state = error.getSQLState();
        return "42S11".equals(state) || "42710".equals(state);
    }
}
