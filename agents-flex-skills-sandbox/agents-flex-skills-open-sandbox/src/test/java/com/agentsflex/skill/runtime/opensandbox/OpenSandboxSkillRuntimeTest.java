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
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkPolicy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
