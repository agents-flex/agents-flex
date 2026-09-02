package com.agentsflex.agent.compression;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentContextCompressionDecidersTest {

    @Test
    public void shouldSupportCommonThresholdConditionsAndComposition() {
        AgentContextCompressionInput input = new AgentContextCompressionInput(
            Arrays.<Message>asList(new UserMessage("one"), new UserMessage("two")),
            Collections.<Message>emptyList(), 120, 2, AgentContextCompressionState.empty());

        assertTrue(AgentContextCompressionDeciders.pendingTokensAtLeast(100).shouldCompress(input));
        assertFalse(AgentContextCompressionDeciders.pendingTokensAtLeast(121).shouldCompress(input));
        assertTrue(AgentContextCompressionDeciders.pendingMessagesAtLeast(2).shouldCompress(input));
        assertTrue(AgentContextCompressionDeciders.pendingTurnsAtLeast(2).shouldCompress(input));
        assertTrue(AgentContextCompressionDeciders.anyOf(
            AgentContextCompressionDeciders.pendingTokensAtLeast(200),
            AgentContextCompressionDeciders.pendingTurnsAtLeast(2)).shouldCompress(input));
        assertFalse(AgentContextCompressionDeciders.allOf(
            AgentContextCompressionDeciders.pendingTokensAtLeast(200),
            AgentContextCompressionDeciders.pendingTurnsAtLeast(2)).shouldCompress(input));
    }

    @Test
    public void shouldDefineEmptyAndConstantConditions() {
        AgentContextCompressionInput input = new AgentContextCompressionInput(
            Collections.<Message>emptyList(), Collections.<Message>emptyList(), 0, 0,
            AgentContextCompressionState.empty());

        assertTrue(AgentContextCompressionDeciders.always().shouldCompress(input));
        assertFalse(AgentContextCompressionDeciders.never().shouldCompress(input));
        assertFalse(AgentContextCompressionDeciders.anyOf().shouldCompress(input));
        assertTrue(AgentContextCompressionDeciders.allOf().shouldCompress(input));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeThreshold() {
        AgentContextCompressionDeciders.pendingTokensAtLeast(-1);
    }

    @Test
    public void shouldRejectNegativeMessageAndTurnThresholds() {
        try {
            AgentContextCompressionDeciders.pendingMessagesAtLeast(-1);
            org.junit.Assert.fail("negative message threshold must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("threshold"));
        }
        try {
            AgentContextCompressionDeciders.pendingTurnsAtLeast(-1);
            org.junit.Assert.fail("negative turn threshold must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("threshold"));
        }
    }
}
