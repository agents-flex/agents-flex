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

import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillRuntimeException;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkPolicy;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OpenSandboxSkillRuntimeTest {

    @Test(expected = IllegalStateException.class)
    public void requiresConnectionConfiguration() {
        OpenSandboxSkillRuntime.builder().build();
    }

    @Test
    public void buildsWithoutCreatingSandboxEagerly() {
        ConnectionConfig config = ConnectionConfig.builder()
            .domain("localhost:8080")
            .apiKey("test-key")
            .build();

        OpenSandboxSkillRuntime runtime = OpenSandboxSkillRuntime.builder()
            .connectionConfig(config)
            .build();

        assertEquals("open-sandbox", runtime.getName());
        runtime.close();
    }

    @Test
    public void usesSameConversationKeyAcrossRuntimeInstances() throws Exception {
        ConnectionConfig config = connectionConfig("test-key");
        OpenSandboxSkillRuntime first = conversationRuntime(config, "shared-conversation");
        OpenSandboxSkillRuntime second = conversationRuntime(config, "shared-conversation");

        try {
            assertEquals(conversationKey(first), conversationKey(second));
        } finally {
            first.destroyConversationSandbox();
        }
    }

    @Test
    public void keepsConversationIdentityAcrossApiKeyRotation() throws Exception {
        OpenSandboxSkillRuntime first = conversationRuntime(connectionConfig("old-key"),
            "rotated-key-conversation");
        OpenSandboxSkillRuntime second = conversationRuntime(connectionConfig("new-key"),
            "rotated-key-conversation");

        try {
            assertEquals(conversationKey(first), conversationKey(second));
        } finally {
            first.destroyConversationSandbox();
        }
    }

    @Test
    public void isolatesDifferentOpenSandboxServices() throws Exception {
        OpenSandboxSkillRuntime first = conversationRuntime(connectionConfig("test-key"),
            "service-conversation");
        ConnectionConfig otherService = ConnectionConfig.builder()
            .domain("localhost:8081")
            .apiKey("test-key")
            .build();
        OpenSandboxSkillRuntime second = conversationRuntime(otherService, "service-conversation");

        try {
            assertNotEquals(conversationKey(first), conversationKey(second));
        } finally {
            first.destroyConversationSandbox();
            second.destroyConversationSandbox();
        }
    }

    @Test
    public void isolatesDifferentConversations() throws Exception {
        ConnectionConfig config = connectionConfig("test-key");
        OpenSandboxSkillRuntime first = conversationRuntime(config, "conversation-one");
        OpenSandboxSkillRuntime second = conversationRuntime(config, "conversation-two");

        try {
            assertNotEquals(conversationKey(first), conversationKey(second));
        } finally {
            first.destroyConversationSandbox();
            second.destroyConversationSandbox();
        }
    }

    @Test
    public void configuresConversationWorkspaceWithoutCreatingSandbox() {
        ConnectionConfig config = ConnectionConfig.builder()
            .domain("localhost:8080")
            .apiKey("test-key")
            .build();
        OpenSandboxSkillRuntime runtime = OpenSandboxSkillRuntime.builder()
            .connectionConfig(config)
            .conversationsRoot("/workspace/conversations")
            .conversationId("conversation-a")
            .build();

        assertEquals("/workspace/conversations/conversation-a", runtime.getDefaultWorkingDirectory());
        try {
            runtime.getFileSystem().readText("../conversation-b/private.txt", 100);
            fail("Expected cross-conversation file access to be rejected");
        } catch (SkillRuntimeException expected) {
            assertTrue(expected.getMessage().contains("outside the conversation workspace"));
        }
        try {
            runtime.execute(new SkillExecutionRequest("pwd", "/tmp", 5000,
                Collections.<String, String>emptyMap()));
            fail("Expected working directory outside the conversation workspace to be rejected");
        } catch (SkillRuntimeException expected) {
            assertTrue(expected.getMessage().contains("outside the conversation workspace"));
        }
        runtime.destroyConversationSandbox();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeConversationId() {
        OpenSandboxSkillRuntime.builder().conversationId("..");
    }

    @Test
    public void buildsConnectionConfigurationWithConfigurer() {
        OpenSandboxSkillRuntime runtime = OpenSandboxSkillRuntime.builder()
            .connectionConfig(connection -> connection
                .domain("localhost:8080")
                .apiKey("test-key"))
            .build();

        assertEquals("open-sandbox", runtime.getName());
        runtime.close();
    }

    @Test
    public void buildsNetworkPolicyWithConfigurer() {
        OpenSandboxSkillRuntime runtime = OpenSandboxSkillRuntime.builder()
            .connectionConfig(connection -> connection
                .domain("localhost:8080")
                .apiKey("test-key"))
            .networkPolicy(policy -> policy
                .defaultAction(NetworkPolicy.DefaultAction.DENY))
            .build();

        assertEquals("open-sandbox", runtime.getName());
        runtime.close();
    }

    private static ConnectionConfig connectionConfig(String apiKey) {
        return ConnectionConfig.builder()
            .domain("localhost:8080")
            .apiKey(apiKey)
            .build();
    }

    private static OpenSandboxSkillRuntime conversationRuntime(ConnectionConfig config,
                                                                String conversationId) {
        return OpenSandboxSkillRuntime.builder()
            .connectionConfig(config)
            .conversationId(conversationId)
            .build();
    }

    private static Object conversationKey(OpenSandboxSkillRuntime runtime) throws Exception {
        Field field = OpenSandboxSkillRuntime.class.getDeclaredField("conversationKey");
        field.setAccessible(true);
        return field.get(runtime);
    }
}
