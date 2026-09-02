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
    public void incrementalFactoryBuildsProcessorWithoutExposingAssemblyAtCallSite() {
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
            input -> true,
            compressor,
            messages -> messages.size());

        assertTrue(policy.isIncremental());
        assertTrue(policy.getCompressor() == null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void policyRejectsPartialIncrementalConfiguration() {
        AgentContextCompressionPolicy.builder()
            .decider(input -> true)
            .build();
    }

    @Test
    public void immediatePolicyRejectsIncrementalOperation() {
        try {
            AgentContextCompressionPolicy.immediate(messages -> Collections.emptyList())
                .compress("conversation", Collections.emptyList());
            org.junit.Assert.fail("immediate policy must not expose incremental operation");
        } catch (IllegalStateException expected) {
            org.junit.Assert.assertTrue(expected.getMessage().contains("not incremental"));
        }
    }

    @Test
    public void policyRejectsEveryIncompleteIncrementalDependency() {
        AgentContextCompressor compressor = messages -> Collections.emptyList();
        AgentContextCompressionDecider decider = input -> true;
        AgentContextCompressionStateStore store = new AgentContextCompressionStateStore() {
            public AgentContextCompressionState load(String id) {
                return null;
            }

            public boolean save(String id, AgentContextCompressionState state, long version) {
                return true;
            }
        };
        try {
            AgentContextCompressionPolicy.builder().stateStore(store).build();
            org.junit.Assert.fail("missing incremental dependencies must fail");
        } catch (IllegalArgumentException expected) {
        }
        try {
            AgentContextCompressionPolicy.builder().decider(decider).build();
            org.junit.Assert.fail("missing incremental dependencies must fail");
        } catch (IllegalArgumentException expected) {
        }
        try {
            AgentContextCompressionPolicy.builder().tokenEstimator(messages -> 1).build();
            org.junit.Assert.fail("missing incremental dependencies must fail");
        } catch (IllegalArgumentException expected) {
        }
        try {
            AgentContextCompressionPolicy.builder().stateStore(store).decider(decider)
                .compressor(compressor).tokenEstimator(messages -> 1).build();
        } catch (RuntimeException unexpected) {
            org.junit.Assert.fail("complete incremental configuration must be accepted");
        }
    }
}
