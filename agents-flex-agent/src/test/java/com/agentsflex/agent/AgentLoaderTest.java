package com.agentsflex.agent;

import com.agentsflex.agent.loader.AgentLoader;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** 验证 AgentLoader 的精确版本、当前生效版本和业务自定义加载语义。 */
public class AgentLoaderTest {

    @Test
    public void shouldLoadExactAndActiveAgentWithoutRegistration() {
        Agent version1 = agent("business-agent", "1");
        Agent version2 = agent("business-agent", "2");
        InMemoryAgentLoader loader = new InMemoryAgentLoader(version1, version2);

        assertSame(version1, loader.load("business-agent", "1"));
        assertSame(version2, loader.load("business-agent", "2"));
        assertSame(version2, loader.loadActive("business-agent"));
        assertNull(loader.load("business-agent", "3"));
    }

    @Test
    public void shouldAllowBusinessTablesToAssembleAgentDirectly() {
        AgentLoader loader = new AgentLoader() {
            @Override
            public Agent load(String agentId, String version) {
                return agent(agentId, version);
            }

            @Override
            public Agent loadActive(String agentId) {
                return agent(agentId, "published-revision");
            }
        };

        assertEquals("history-revision", loader.load("database-agent", "history-revision").getVersion());
        assertEquals("published-revision", loader.loadActive("database-agent").getVersion());
    }

    private static Agent agent(String id, String version) {
        return Agent.builder("Loader Agent")
            .id(id)
            .version(version)
            .chatModel(new AgentScenarioTestSupport.QueueChatModel())
            .build();
    }
}
