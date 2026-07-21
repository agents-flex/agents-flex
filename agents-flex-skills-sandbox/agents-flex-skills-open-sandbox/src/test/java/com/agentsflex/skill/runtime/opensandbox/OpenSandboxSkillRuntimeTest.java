package com.agentsflex.skill.runtime.opensandbox;

import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
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
}
