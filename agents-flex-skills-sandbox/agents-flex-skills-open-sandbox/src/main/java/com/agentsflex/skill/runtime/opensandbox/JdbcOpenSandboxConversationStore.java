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
package com.agentsflex.skill.runtime.opensandbox;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用 JDBC 持久化 OpenSandbox 会话状态。
 *
 * <p>数据库主键保证 {@link #create(OpenSandboxConversationRecord)} 的跨 JVM 原子性。
 * Store 不负责串行化同一会话的业务请求，调用方应避免并发修改同一个工作目录。</p>
 */
public final class JdbcOpenSandboxConversationStore implements OpenSandboxConversationStore {

    public static final String DEFAULT_TABLE = "agents_flex_open_sandbox_conversations";

    private final DataSource dataSource;
    private final String selectSql;
    private final String selectForUpdateSql;
    private final String insertSql;
    private final String updateSql;
    private final String deleteSql;

    public JdbcOpenSandboxConversationStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE);
    }

    public JdbcOpenSandboxConversationStore(DataSource dataSource, String tableName) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        if (tableName == null || !tableName.matches("[A-Za-z0-9_.$]+")) {
            throw new IllegalArgumentException("tableName contains unsupported characters: " + tableName);
        }
        this.dataSource = dataSource;
        String columns = "service_key,conversation_id,workspace_root,sandbox_id,workspace_ready,prepared_skills";
        this.selectSql = "SELECT " + columns + " FROM " + tableName + " WHERE storage_key=?";
        this.selectForUpdateSql = selectSql + " FOR UPDATE";
        this.insertSql = "INSERT INTO " + tableName + " (storage_key,service_key,conversation_id,"
            + "workspace_root,sandbox_id,workspace_ready,prepared_skills,updated_at) VALUES (?,?,?,?,?,?,?,?)";
        this.updateSql = "UPDATE " + tableName + " SET sandbox_id=?,workspace_ready=?,prepared_skills=?,"
            + "updated_at=? WHERE storage_key=?";
        this.deleteSql = "DELETE FROM " + tableName + " WHERE storage_key=?";
    }

    @Override
    public OpenSandboxConversationRecord get(OpenSandboxConversationKey key) {
        try (Connection connection = dataSource.getConnection()) {
            return select(connection, selectSql, key);
        } catch (SQLException e) {
            throw new OpenSandboxConversationStoreException("Failed to read OpenSandbox conversation", e);
        }
    }

    @Override
    public boolean create(OpenSandboxConversationRecord record) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(insertSql)) {
            bindInsert(statement, record);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            if (isConstraintViolation(e) && get(record.getKey()) != null) {
                return false;
            }
            throw new OpenSandboxConversationStoreException("Failed to create OpenSandbox conversation", e);
        }
    }

    @Override
    public void update(OpenSandboxConversationRecord record) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setString(1, record.getSandboxId());
            statement.setBoolean(2, record.isWorkspaceReady());
            statement.setString(3, encodePreparedSkills(record.getPreparedSkills()));
            statement.setLong(4, System.currentTimeMillis());
            statement.setString(5, record.getKey().getStorageKey());
            if (statement.executeUpdate() != 1) {
                throw new OpenSandboxConversationStoreException(
                    "OpenSandbox conversation does not exist: " + record.getKey().getStorageKey(), null);
            }
        } catch (SQLException e) {
            throw new OpenSandboxConversationStoreException("Failed to update OpenSandbox conversation", e);
        }
    }

    @Override
    public OpenSandboxConversationRecord delete(OpenSandboxConversationKey key) {
        Connection connection = null;
        boolean originalAutoCommit = true;
        try {
            connection = dataSource.getConnection();
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            OpenSandboxConversationRecord record = select(connection, selectForUpdateSql, key);
            if (record != null) {
                try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
                    statement.setString(1, key.getStorageKey());
                    statement.executeUpdate();
                }
            }
            connection.commit();
            return record;
        } catch (SQLException e) {
            rollback(connection);
            throw new OpenSandboxConversationStoreException("Failed to delete OpenSandbox conversation", e);
        } finally {
            restoreAndClose(connection, originalAutoCommit);
        }
    }

    private OpenSandboxConversationRecord select(Connection connection, String sql,
                                                 OpenSandboxConversationKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.getStorageKey());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                OpenSandboxConversationKey storedKey = OpenSandboxConversationKey.of(
                    result.getString("service_key"), result.getString("conversation_id"));
                if (!storedKey.equals(key)) {
                    throw new OpenSandboxConversationStoreException(
                        "Stored OpenSandbox conversation key does not match its primary key", null);
                }
                return new OpenSandboxConversationRecord(storedKey, result.getString("workspace_root"),
                    result.getString("sandbox_id"), result.getBoolean("workspace_ready"),
                    decodePreparedSkills(result.getString("prepared_skills")));
            }
        }
    }

    private static void bindInsert(PreparedStatement statement, OpenSandboxConversationRecord record)
        throws SQLException {
        int index = 1;
        statement.setString(index++, record.getKey().getStorageKey());
        statement.setString(index++, record.getKey().getServiceKey());
        statement.setString(index++, record.getKey().getConversationId());
        statement.setString(index++, record.getWorkspaceRoot());
        statement.setString(index++, record.getSandboxId());
        statement.setBoolean(index++, record.isWorkspaceReady());
        statement.setString(index++, encodePreparedSkills(record.getPreparedSkills()));
        statement.setLong(index, System.currentTimeMillis());
    }

    private static String encodePreparedSkills(Map<String, String> preparedSkills) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : preparedSkills.entrySet()) {
            result.append(encoder.encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8)))
                .append(':')
                .append(encoder.encodeToString(entry.getValue().getBytes(StandardCharsets.UTF_8)))
                .append('\n');
        }
        return result.toString();
    }

    private static Map<String, String> decodePreparedSkills(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return Collections.emptyMap();
        }
        Base64.Decoder decoder = Base64.getUrlDecoder();
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : encoded.split("\\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new OpenSandboxConversationStoreException(
                    "Stored prepared skill data is malformed", null);
            }
            String key = new String(decoder.decode(line.substring(0, separator)), StandardCharsets.UTF_8);
            String value = new String(decoder.decode(line.substring(separator + 1)), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    private static boolean isConstraintViolation(SQLException exception) {
        String state = exception.getSQLState();
        return state != null && state.startsWith("23");
    }

    private static void rollback(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 保留调用方即将收到的原始异常。
        }
    }

    private static void restoreAndClose(Connection connection, boolean originalAutoCommit) {
        if (connection == null) {
            return;
        }
        try {
            if (connection.getAutoCommit() != originalAutoCommit) {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException ignored) {
            // 连接即将归还或关闭，恢复失败不覆盖事务结果。
        } finally {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // close 没有可恢复动作。
            }
        }
    }
}
