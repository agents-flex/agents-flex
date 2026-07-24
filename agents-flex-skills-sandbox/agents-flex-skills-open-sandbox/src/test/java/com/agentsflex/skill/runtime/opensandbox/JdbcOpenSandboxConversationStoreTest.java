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

import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JdbcOpenSandboxConversationStoreTest {

    private DataSource dataSource;

    @Before
    public void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:open_sandbox_store;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        source.setUser("sa");
        this.dataSource = source;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS agents_flex_open_sandbox_conversations");
            statement.execute("CREATE TABLE agents_flex_open_sandbox_conversations ("
                + "storage_key VARCHAR(64) PRIMARY KEY,"
                + "service_key VARCHAR(128) NOT NULL,"
                + "conversation_id VARCHAR(128) NOT NULL,"
                + "workspace_root VARCHAR(1024) NOT NULL,"
                + "sandbox_id VARCHAR(255),"
                + "workspace_ready BOOLEAN NOT NULL,"
                + "prepared_skills CLOB NOT NULL,"
                + "updated_at BIGINT NOT NULL)");
        }
    }

    @Test
    public void persistsStateAcrossIndependentStoreInstances() {
        OpenSandboxConversationKey key = key("conversation-a");
        Map<String, String> preparedSkills = new LinkedHashMap<>();
        preparedSkills.put("/skills/pptx", "/workspace/conversations/conversation-a/skills/pptx");
        OpenSandboxConversationRecord record = new OpenSandboxConversationRecord(
            key, workspace(key), "sandbox-123", true, preparedSkills);

        JdbcOpenSandboxConversationStore first = new JdbcOpenSandboxConversationStore(dataSource);
        assertTrue(first.create(record));

        JdbcOpenSandboxConversationStore second = new JdbcOpenSandboxConversationStore(dataSource);
        OpenSandboxConversationRecord stored = second.get(key);
        assertEquals("sandbox-123", stored.getSandboxId());
        assertTrue(stored.isWorkspaceReady());
        assertEquals(preparedSkills, stored.getPreparedSkills());
    }

    @Test
    public void createsOnlyOneRecordForTheSameConversation() {
        OpenSandboxConversationKey key = key("conversation-create");
        JdbcOpenSandboxConversationStore first = new JdbcOpenSandboxConversationStore(dataSource);
        JdbcOpenSandboxConversationStore second = new JdbcOpenSandboxConversationStore(dataSource);

        assertTrue(first.create(record(key, "sandbox-first")));
        assertFalse(second.create(record(key, "sandbox-second")));
        assertEquals("sandbox-first", second.get(key).getSandboxId());
    }

    @Test
    public void updatesExistingConversation() {
        OpenSandboxConversationKey key = key("conversation-update");
        JdbcOpenSandboxConversationStore store = new JdbcOpenSandboxConversationStore(dataSource);
        assertTrue(store.create(record(key, "sandbox-before")));

        Map<String, String> preparedSkills = Collections.singletonMap("local", "remote");
        store.update(new OpenSandboxConversationRecord(
            key, workspace(key), "sandbox-after", true, preparedSkills));

        OpenSandboxConversationRecord stored = store.get(key);
        assertEquals("sandbox-after", stored.getSandboxId());
        assertTrue(stored.isWorkspaceReady());
        assertEquals(preparedSkills, stored.getPreparedSkills());
    }

    @Test(expected = OpenSandboxConversationStoreException.class)
    public void rejectsUpdateForMissingConversation() {
        OpenSandboxConversationKey key = key("conversation-missing");
        new JdbcOpenSandboxConversationStore(dataSource).update(record(key, "sandbox-missing"));
    }

    @Test
    public void deletesAndReturnsPersistentConversationState() {
        OpenSandboxConversationKey key = key("conversation-delete");
        JdbcOpenSandboxConversationStore store = new JdbcOpenSandboxConversationStore(dataSource);
        assertTrue(store.create(record(key, "sandbox-delete")));

        OpenSandboxConversationRecord removed = store.delete(key);

        assertEquals("sandbox-delete", removed.getSandboxId());
        assertNull(store.get(key));
        assertNull(store.delete(key));
    }

    @Test
    public void runtimeDestroyDoesNotCreateJdbcRecord() throws Exception {
        JdbcOpenSandboxConversationStore store = new JdbcOpenSandboxConversationStore(dataSource);
        OpenSandboxSkillRuntime runtime = OpenSandboxSkillRuntime.builder()
            .connectionConfig(ConnectionConfig.builder()
                .domain("localhost:8080")
                .apiKey("test-key")
                .build())
            .conversationStore(store)
            .conversationId("runtime-destroy")
            .build();

        runtime.destroyConversationSandbox();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT COUNT(*) FROM agents_flex_open_sandbox_conversations")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
        }
    }

    private static OpenSandboxConversationRecord record(OpenSandboxConversationKey key, String sandboxId) {
        return new OpenSandboxConversationRecord(key, workspace(key), sandboxId, false,
            Collections.<String, String>emptyMap());
    }

    private static OpenSandboxConversationKey key(String conversationId) {
        return OpenSandboxConversationKey.of("service-key", conversationId);
    }

    private static String workspace(OpenSandboxConversationKey key) {
        return "/workspace/conversations/" + key.getConversationId();
    }
}
