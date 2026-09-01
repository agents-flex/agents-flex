/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.exception.AgentFormRequiredException;
import com.agentsflex.agent.tool.AgentFormDefinition;
import com.agentsflex.agent.tool.AgentUserInputTool;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolExecutionTarget;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Agent 运行时值对象的参数校验和不可变性契约测试。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class AgentValueObjectContractTest {

    @Test
    public void shouldValidateBudgetBoundaries() {
        AgentBudget unlimited = AgentBudget.unlimited();
        assertEquals(0, unlimited.getMaxDurationMillis());
        assertEquals(0, unlimited.getMaxToolCalls());

        assertBuildFailure(() -> AgentBudget.builder().maxDurationMillis(-1).build());
        assertBuildFailure(() -> AgentBudget.builder().maxInputTokens(-1).build());
        assertBuildFailure(() -> AgentBudget.builder().maxOutputTokens(-1).build());
        assertBuildFailure(() -> AgentBudget.builder().maxTotalTokens(-1).build());
        assertBuildFailure(() -> AgentBudget.builder().maxToolCalls(-1).build());
    }

    @Test
    public void shouldCalculateRetryBackoffAndCapMaximumDelay() {
        AgentRetryPolicy policy = AgentRetryPolicy.builder()
            .maxRetries(5)
            .initialDelayMillis(100)
            .maxDelayMillis(350)
            .multiplier(2)
            .build();

        assertEquals(100, policy.delayMillis(0));
        assertEquals(100, policy.delayMillis(1));
        assertEquals(200, policy.delayMillis(2));
        assertEquals(350, policy.delayMillis(3));
        assertEquals(350, policy.delayMillis(10));
    }

    @Test
    public void shouldRejectInvalidRetryPolicy() {
        assertBuildFailure(() -> AgentRetryPolicy.builder().maxRetries(-1).build());
        assertBuildFailure(() -> AgentRetryPolicy.builder().initialDelayMillis(-1).build());
        assertBuildFailure(() -> AgentRetryPolicy.builder()
            .initialDelayMillis(10).maxDelayMillis(9).build());
        assertBuildFailure(() -> AgentRetryPolicy.builder().multiplier(0.9).build());
    }

    @Test
    public void shouldKeepApprovalDecisionMetadataImmutable() {
        ToolApprovalDecision decision = ToolApprovalDecision.requireApproval()
            .code("REVIEW")
            .message("review required")
            .reason("risk")
            .metadata("level", "high")
            .build();

        assertEquals(ToolApprovalDecision.Outcome.REQUIRE_APPROVAL,
            decision.getOutcome());
        assertEquals("REVIEW", decision.getCode());
        assertEquals("high", decision.getMetadata().get("level"));
        try {
            decision.getMetadata().put("other", "value");
            fail("approval metadata must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, decision.getMetadata().size());
        }
    }

    @Test
    public void shouldCopyResumeCommandMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reviewer", "alice");
        AgentResumeCommand command = new AgentResumeCommand(
            AgentResumeCommandType.APPROVE_TOOL, null, "call-1", metadata);
        metadata.put("reviewer", "bob");

        assertEquals("alice", command.getMetadata().get("reviewer"));
        AgentResumeCommand enriched = command.withMetadata("ticket", "T-1");
        assertFalse(command.getMetadata().containsKey("ticket"));
        assertEquals("T-1", enriched.getMetadata().get("ticket"));
    }

    @Test
    public void shouldCopyStructuredUserInputData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("priority", "HIGH");
        AgentResumeCommand command = AgentResumeCommand.userInput("input-1", data);
        data.put("priority", "LOW");

        assertEquals("input-1", command.getCorrelationId());
        assertEquals("HIGH", command.getData().get("priority"));
        try {
            command.getData().put("other", true);
            fail("user input data must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, command.getData().size());
        }
    }

    @Test
    public void shouldBuildExternalToolAndNormalizeResumeResults() {
        Tool external = Tool.builder("browser", "browser")
            .executionTarget(ToolExecutionTarget.EXTERNAL)
            .build();
        assertEquals(ToolExecutionTarget.EXTERNAL, external.getExecutionTarget());
        assertEquals(ToolExecutionTarget.LOCAL,
            Tool.builder("local", "local").build().getExecutionTarget());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        AgentResumeCommand command = AgentResumeCommand.toolResult("call-1", result);
        assertEquals(AgentResumeCommandType.TOOL_RESULT, command.getType());
        assertEquals("call-1", command.getCorrelationId());
        assertEquals("{\"ok\":true}", command.getContent());
        assertTrue(AgentResumeCommand.toolError("call-1", "FAILED", "bad")
            .getContent().contains("FAILED"));
    }

    @Test
    public void shouldValidateUserInputFormDefinitions() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        AgentFormDefinition form = AgentFormDefinition.builder("ticket")
            .description("缺少工单信息时使用")
            .schema(schema)
            .build();
        schema.put("title", "changed");

        assertFalse(form.getSchema().containsKey("title"));
        try {
            form.getSchema().put("title", "changed");
            fail("form schema must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, form.getSchema().size());
        }

        assertBuildFailure(() -> AgentFormDefinition.builder("")
            .description("condition").schema(Collections.singletonMap("type", "object")).build());
        assertBuildFailure(() -> AgentFormDefinition.builder("ticket")
            .schema(Collections.singletonMap("type", "object")).build());
        assertBuildFailure(() -> AgentFormDefinition.builder("ticket")
            .description("condition").schema(Collections.emptyMap()).build());
        assertBuildFailure(() -> AgentUserInputTool.builder().build());
        assertIllegalArgument(() -> AgentUserInputTool.builder().form(form).form(form));
        assertIllegalArgument(() -> new AgentFormRequiredException(null));
    }

    @Test
    public void shouldValidateExecutionPolicyRequiredValues() {
        assertBuildFailure(() -> AgentExecutionPolicy.builder().maxIterations(0).build());
        assertBuildFailure(() -> AgentExecutionPolicy.builder().maxSteps(0).build());
        assertBuildFailure(() -> AgentExecutionPolicy.builder().toolErrorStrategy(null).build());
        assertBuildFailure(() -> AgentExecutionPolicy.builder().retryPolicy(null).build());
        assertBuildFailure(() -> AgentExecutionPolicy.builder().budget(null).build());
    }

    @Test
    public void shouldCopyEventData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "before");
        AgentEvent event = new AgentEvent("turn", "agent", "1", 1,
            AgentEventType.TURN_STARTED, data);
        data.put("status", "after");

        assertEquals("before", event.getData().get("status"));
        assertTrue(event.getOccurredAt() > 0);
        assertTrue(event.getEventId() != null && !event.getEventId().isEmpty());
    }

    private void assertBuildFailure(Runnable runnable) {
        try {
            runnable.run();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }

    private void assertIllegalArgument(Runnable runnable) {
        try {
            runnable.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }
}
