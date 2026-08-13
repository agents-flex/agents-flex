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

import com.agentsflex.asynctask.store.AsyncTaskStoreSerializer;
import com.agentsflex.asynctask.store.FastjsonAsyncTaskStoreSerializer;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * JDBC 异步任务 Store 的不可变配置与工厂入口。
 */
public final class JdbcAsyncTaskStoreConfig {
    private final DataSource dataSource;
    private final String tablePrefix;
    private final String binaryColumnType;
    private final AsyncTaskStoreSerializer serializer;

    private JdbcAsyncTaskStoreConfig(Builder builder) {
        dataSource = builder.dataSource;
        tablePrefix = builder.tablePrefix;
        binaryColumnType = builder.binaryColumnType;
        serializer = builder.serializer;
    }

    /**
     * 以指定数据源创建配置构建器。
     */
    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public String getBinaryColumnType() {
        return binaryColumnType;
    }

    AsyncTaskStoreSerializer serializer() {
        return serializer;
    }

    /**
     * 返回负责初始化表和索引的 Schema 对象。
     */
    public JdbcAsyncTaskStoreSchema schema() {
        return new JdbcAsyncTaskStoreSchema(this);
    }

    /**
     * 返回可直接交给 AsyncTaskManager/Worker 使用的 Store。
     */
    public JdbcAsyncTaskStore store() {
        return new JdbcAsyncTaskStore(this);
    }

    /**
     * 配置构建器；表名和列类型会校验字符集，避免被拼接为 SQL 注入。
     */
    public static final class Builder {
        private final DataSource dataSource;
        private String tablePrefix = "af_async_";
        private String binaryColumnType = "BLOB";
        private AsyncTaskStoreSerializer serializer = new FastjsonAsyncTaskStoreSerializer();

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        }

        public Builder tablePrefix(String value) {
            if (value == null || !value.matches("[A-Za-z0-9_]+"))
                throw new IllegalArgumentException("tablePrefix contains unsupported characters");
            tablePrefix = value;
            return this;
        }

        public Builder binaryColumnType(String value) {
            if (value == null || !value.matches("[A-Za-z0-9_(), ]+"))
                throw new IllegalArgumentException("binaryColumnType contains unsupported characters");
            binaryColumnType = value;
            return this;
        }

        public Builder serializer(AsyncTaskStoreSerializer value) {
            serializer = Objects.requireNonNull(value, "serializer must not be null");
            return this;
        }

        public JdbcAsyncTaskStoreConfig build() {
            return new JdbcAsyncTaskStoreConfig(this);
        }
    }
}
