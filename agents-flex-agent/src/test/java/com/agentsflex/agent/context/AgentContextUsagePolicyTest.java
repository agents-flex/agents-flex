package com.agentsflex.agent.context;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 验证服务端 Usage 策略的默认值、配置暴露和全部参数边界。
 */
public class AgentContextUsagePolicyTest {

    /**
     * 验证默认策略保持关闭，并使用保守的阈值和恢复次数。
     */
    @Test
    public void defaultsMustRemainDisabledAndConservative() {
        AgentContextUsagePolicy policy = AgentContextUsagePolicy.disabled();

        assertFalse(policy.isEnabled());
        assertNull(policy.getStateStore());
        assertEquals(0.85d, policy.getCompressionThresholdRatio(), 0d);
        assertEquals(0, policy.getReservedOutputTokens());
        assertTrue(policy.isCompressOnTokenLimitExceeded());
        assertEquals(1, policy.getMaxTokenLimitRecoveryAttempts());
    }

    /**
     * 验证启用策略后所有显式配置均可无损读取。
     */
    @Test
    public void enabledPolicyMustExposeEveryConfiguredValue() {
        InMemoryAgentContextUsageStateStore store = new InMemoryAgentContextUsageStateStore();
        AgentContextUsagePolicy policy = AgentContextUsagePolicy.builder()
            .enabled(true)
            .stateStore(store)
            .compressionThresholdRatio(1d)
            .reservedOutputTokens(4096)
            .compressOnTokenLimitExceeded(false)
            .maxTokenLimitRecoveryAttempts(3)
            .build();

        assertTrue(policy.isEnabled());
        assertSame(store, policy.getStateStore());
        assertEquals(1d, policy.getCompressionThresholdRatio(), 0d);
        assertEquals(4096, policy.getReservedOutputTokens());
        assertFalse(policy.isCompressOnTokenLimitExceeded());
        assertEquals(3, policy.getMaxTokenLimitRecoveryAttempts());
    }

    /**
     * 验证启用 Usage 管理时必须提供状态 Store。
     */
    @Test(expected = IllegalArgumentException.class)
    public void enabledPolicyMustRequireStateStore() {
        AgentContextUsagePolicy.builder().enabled(true).build();
    }

    /**
     * 验证阈值拒绝越界值、NaN 和正负无穷。
     */
    @Test
    public void thresholdMustRejectNonFiniteAndOutOfRangeValues() {
        assertInvalidThreshold(0d);
        assertInvalidThreshold(-0.1d);
        assertInvalidThreshold(1.01d);
        assertInvalidThreshold(Double.NaN);
        assertInvalidThreshold(Double.POSITIVE_INFINITY);
        assertInvalidThreshold(Double.NEGATIVE_INFINITY);
    }

    /**
     * 验证输出预留和恢复次数不得为负数。
     */
    @Test
    public void limitsMustRejectNegativeValues() {
        try {
            AgentContextUsagePolicy.builder().reservedOutputTokens(-1).build();
            fail("Expected reserved output validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not be negative"));
        }
        try {
            AgentContextUsagePolicy.builder().maxTokenLimitRecoveryAttempts(-1).build();
            fail("Expected recovery attempt validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not be negative"));
        }
    }

    /**
     * 断言给定阈值无法通过策略构建校验。
     */
    private static void assertInvalidThreshold(double value) {
        try {
            AgentContextUsagePolicy.builder().compressionThresholdRatio(value).build();
            fail("Expected invalid threshold: " + value);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("compressionThresholdRatio"));
        }
    }
}
