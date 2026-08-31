package com.agentsflex.agent.compression;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AgentContextCompressionPolicyTest {

    @Test
    public void immediatePolicyExposesCompressorAndDefaults() {
        AgentContextCompressor compressor = messages -> Collections.<Message>singletonList(
            new UserMessage("summary"));
        AgentContextCompressionPolicy policy =
            AgentContextCompressionPolicy.immediate(compressor);

        assertFalse(policy.isIncremental());
        assertSame(compressor, policy.getCompressor());
        assertTrue(policy.isCompactCompletedToolTurns());
        org.junit.Assert.assertEquals(2, policy.getKeepRecentTurns());
    }

    @Test
    public void incrementalFactoryBuildsCoordinatorWithoutExposingAssemblyAtCallSite() {
        AgentContextCompressionStateStore store = new AgentContextCompressionStateStore() {
            public AgentContextCompressionState load(String id) {
                return null;
            }

            public boolean save(String id, AgentContextCompressionState state, long version) {
                return true;
            }
        };
        AgentContextCompressor compressor = messages ->
            Collections.<Message>singletonList(new UserMessage("summary"));
        AgentContextCompressionPolicy policy = AgentContextCompressionPolicy.incremental(
            store,
            (pending, tokens, turns, state) -> true,
            compressor,
            messages -> messages.size());

        assertTrue(policy.isIncremental());
        assertTrue(policy.getCompressor() == null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void policyRejectsPartialIncrementalConfiguration() {
        AgentContextCompressionPolicy.builder()
            .trigger((pending, tokens, turns, state) -> true)
            .build();
    }
}
