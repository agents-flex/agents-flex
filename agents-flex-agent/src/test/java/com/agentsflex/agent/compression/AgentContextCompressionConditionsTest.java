package com.agentsflex.agent.compression;

import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentContextCompressionConditionsTest {

    @Test
    public void shouldSupportCommonThresholdConditionsAndComposition() {
        AgentContextCompressionInput input = new AgentContextCompressionInput(
            Arrays.<Message>asList(new UserMessage("one"), new UserMessage("two")),
            Collections.<Message>emptyList(), 120, 2, AgentContextCompressionState.empty());

        assertTrue(AgentContextCompressionConditions.pendingTokensAtLeast(100).shouldCompress(input));
        assertFalse(AgentContextCompressionConditions.pendingTokensAtLeast(121).shouldCompress(input));
        assertTrue(AgentContextCompressionConditions.pendingMessagesAtLeast(2).shouldCompress(input));
        assertTrue(AgentContextCompressionConditions.pendingTurnsAtLeast(2).shouldCompress(input));
        assertTrue(AgentContextCompressionConditions.anyOf(
            AgentContextCompressionConditions.pendingTokensAtLeast(200),
            AgentContextCompressionConditions.pendingTurnsAtLeast(2)).shouldCompress(input));
        assertFalse(AgentContextCompressionConditions.allOf(
            AgentContextCompressionConditions.pendingTokensAtLeast(200),
            AgentContextCompressionConditions.pendingTurnsAtLeast(2)).shouldCompress(input));
    }

    @Test
    public void shouldDefineEmptyAndConstantConditions() {
        AgentContextCompressionInput input = new AgentContextCompressionInput(
            Collections.<Message>emptyList(), Collections.<Message>emptyList(), 0, 0,
            AgentContextCompressionState.empty());

        assertTrue(AgentContextCompressionConditions.always().shouldCompress(input));
        assertFalse(AgentContextCompressionConditions.never().shouldCompress(input));
        assertFalse(AgentContextCompressionConditions.anyOf().shouldCompress(input));
        assertTrue(AgentContextCompressionConditions.allOf().shouldCompress(input));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeThreshold() {
        AgentContextCompressionConditions.pendingTokensAtLeast(-1);
    }

    @Test
    public void shouldRejectNegativeMessageAndTurnThresholds() {
        try {
            AgentContextCompressionConditions.pendingMessagesAtLeast(-1);
            org.junit.Assert.fail("negative message threshold must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("threshold"));
        }
        try {
            AgentContextCompressionConditions.pendingTurnsAtLeast(-1);
            org.junit.Assert.fail("negative turn threshold must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("threshold"));
        }
    }
}
