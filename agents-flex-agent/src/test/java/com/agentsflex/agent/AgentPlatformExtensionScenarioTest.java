/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.event.AgentEvent;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.agent.tool.AgentToolInvocation;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.prompt.MemoryPrompt;
import org.junit.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static com.agentsflex.agent.AgentScenarioTestSupport.tool;
import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** 验证配置平台和审计系统需要的框架扩展边界。 */
public class AgentPlatformExtensionScenarioTest {

    @Test
    public void shouldRestoreRunWithOriginalAgentVersion() {
        AgentScenarioTestSupport.QueueChatModel versionOneModel =
            new AgentScenarioTestSupport.QueueChatModel();
        AgentScenarioTestSupport.QueueChatModel versionTwoModel =
            new AgentScenarioTestSupport.QueueChatModel();
        versionOneModel.enqueue(prompt -> new AiMessage("answer from version 1"));
        versionTwoModel.enqueue(prompt -> new AiMessage("answer from version 2"));

        Agent versionOne = Agent.builder("versioned-agent")
            .id("versioned-agent")
            .version("1")
            .chatModel(versionOneModel)
            .attribute("releaseNote", "initial")
            .build();
        Agent versionTwo = Agent.builder("versioned-agent")
            .id("versioned-agent")
            .version("2")
            .chatModel(versionTwoModel)
            .attribute("releaseNote", "updated")
            .build();

        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(versionOne, versionTwo);
        AgentRunner runner = new AgentRunner(store, registry);
        AgentRun started = runner.start(versionOne, "execute with frozen definition");

        assertSame(versionTwo, registry.loadActive("versioned-agent"));

        AgentRun restored = runner.restore(started.getId());
        AgentRun completed = runner.runUntilBlocked(restored);

        assertEquals("1", restored.getAgent().getVersion());
        assertEquals("answer from version 1", completed.getFinalOutput());
        assertEquals(1, versionOneModel.getCallCount());
        assertEquals(0, versionTwoModel.getCallCount());
        assertEquals("1", store.load(started.getId()).getAgentVersion());
    }

    @Test
    public void shouldPersistPerRunIterationPolicyAcrossRestore() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("again-1", "again", "{}")));
        Agent agent = Agent.builder("recommended-iterations-agent")
            .chatModel(model)
            .tool(tool("again", arguments -> "continue"))
            .executionPolicy(AgentExecutionPolicy.builder().maxIterations(9).build())
            .build();

        AgentRunOptions options = AgentRunOptions.builder()
            .executionPolicy(AgentExecutionPolicy.builder().maxIterations(1).build())
            .metadata("taskType", "simple-query")
            .metadata("recommendationReason", "历史任务通常一次完成")
            .build();
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner firstRunner = new AgentRunner(store, registry);
        AgentRun run = firstRunner.start(agent, "run once", options);

        AgentStepResult firstStep = firstRunner.step(run);
        assertEquals(1, firstStep.getToolMessages().size());
        assertEquals(AgentRunStatus.RUNNING, run.getStatus());
        AgentRunner secondRunner = new AgentRunner(store, registry);
        AgentRun restored = secondRunner.restore(run.getId());
        AgentRun stopped = secondRunner.runUntilBlocked(restored);

        assertEquals(1, restored.getExecutionPolicy().getMaxIterations());
        assertEquals("simple-query", restored.getMetadata().get("taskType"));
        assertEquals(AgentRunStatus.MAX_ITERATIONS_REACHED, stopped.getStatus());
        assertEquals(1, stopped.getIterationCount());
    }

    @Test
    public void shouldResolveToolFromOriginalAgentVersion() {
        AgentScenarioTestSupport.QueueChatModel versionOneModel =
            new AgentScenarioTestSupport.QueueChatModel();
        AgentScenarioTestSupport.QueueChatModel versionTwoModel =
            new AgentScenarioTestSupport.QueueChatModel();
        versionOneModel.enqueue(prompt -> toolCalls(new ToolCall("shared-call", "shared", "{}")));
        versionOneModel.enqueue(prompt -> {
            ToolMessage result = (ToolMessage) prompt.getMessages()
                .get(prompt.getMessages().size() - 1);
            assertEquals("tool-version-1", result.getContent());
            return new AiMessage("version 1 completed");
        });
        Agent versionOne = Agent.builder("versioned-tool-agent")
            .id("versioned-tool-agent")
            .version("1")
            .chatModel(versionOneModel)
            .tool(tool("shared", arguments -> "tool-version-1"))
            .toolApprovalPolicy((run, call, value) ->
                com.agentsflex.agent.tool.ToolApprovalDecision.REQUIRE_APPROVAL)
            .build();
        Agent versionTwo = Agent.builder("versioned-tool-agent")
            .id("versioned-tool-agent")
            .version("2")
            .chatModel(versionTwoModel)
            .tool(tool("shared", arguments -> "tool-version-2"))
            .build();

        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(versionOne, versionTwo);
        AgentRunner runner = new AgentRunner(store, registry);
        AgentRun waiting = runner.run(versionOne, "use version one tool");
        assertEquals(AgentRunStatus.WAITING_FOR_APPROVAL, waiting.getStatus());

        runner.start(versionTwo, "register newer version");
        AgentRun completed = runner.resume(waiting.getId(),
            AgentResumeCommand.approveTool("shared-call"));

        assertEquals("version 1 completed", completed.getFinalOutput());
        assertEquals(0, versionTwoModel.getCallCount());
    }

    @Test
    public void shouldLimitModelContextWithoutLosingSnapshotHistory() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            assertEquals(3, prompt.getMessages().size());
            assertEquals("system instruction", prompt.getMessages().get(0).getTextContent());
            assertEquals("third", prompt.getMessages().get(1).getTextContent());
            assertEquals("fourth", prompt.getMessages().get(2).getTextContent());
            return new AiMessage("done");
        });
        Agent agent = Agent.builder("context-agent")
            .instructions("system instruction")
            .chatModel(model)
            .maxAttachedMessages(2)
            .build();
        MemoryPrompt prompt = new MemoryPrompt();
        prompt.addUserMessage("first");
        prompt.addAiMessage("second");
        prompt.addUserMessage("third");
        prompt.addAiMessage("fourth");

        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));
        AgentRun run = AgentRun.fromPrompt(agent, prompt);
        runner.run(run);

        AgentRunSnapshot snapshot = store.load(run.getId());
        assertEquals(6, snapshot.getState().getMessages().size());
        assertEquals("first", snapshot.getState().getMessages().get(1).getTextContent());
        assertEquals("done", snapshot.getState().getMessages().get(5).getTextContent());
    }

    @Test
    public void shouldExposeStableAgentInvocationToTool() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicReference<AgentToolInvocation> captured = new AtomicReference<>();
        model.enqueue(prompt -> toolCalls(new ToolCall("write-42", "write", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("invocation-agent")
            .id("invocation-agent")
            .version("4")
            .chatModel(model)
            .tool(tool("write", args -> {
                captured.set(AgentToolInvocation.current());
                return "ok";
            }))
            .build();

        AgentRun completed = new AgentRunner().run(agent, "write data");
        AgentToolInvocation invocation = captured.get();

        assertNotNull(invocation);
        assertEquals(completed.getId(), invocation.getRunId());
        assertEquals(completed.getRootRunId(), invocation.getRootRunId());
        assertEquals("invocation-agent", invocation.getAgentId());
        assertEquals("4", invocation.getAgentVersion());
        assertEquals("write-42", invocation.getToolCallId());
        assertEquals(completed.getId() + ":write-42", invocation.getIdempotencyKey());
        assertEquals("write", invocation.getToolName());
    }

    @Test
    public void shouldExposeEventIdentityAndStructuredData() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("audited"));
        Agent agent = Agent.builder("audit-agent")
            .version("2026.07.31")
            .chatModel(model)
            .build();
        List<AgentEvent> events = new ArrayList<>();
        InMemoryAgentLoader agentLoader = new InMemoryAgentLoader(agent);
        AgentRunner runner = new AgentRunner(
            new InMemoryAgentRunStore(), agentLoader).addEventListener(events::add);
        AgentRunOptions options = AgentRunOptions.builder()
            .metadata("accountId", "user-42")
            .metadata("module", "agent-console")
            .build();

        runner.run(agent, "audit this call", options);
        AgentEvent modelStarted = find(events, AgentEventType.MODEL_STARTED);

        assertNotNull(modelStarted);
        assertEquals("audit-agent", modelStarted.getAgentId());
        assertEquals("2026.07.31", modelStarted.getAgentVersion());
        assertEquals(1, modelStarted.getData().get("iteration"));
        assertEquals(19, modelStarted.getData().get("remainingIterations"));
        assertTrue(events.get(events.size() - 1).getSequence() > modelStarted.getSequence());
    }

    @Test
    public void shouldStopBuiltInExecutionAtMaximumStepCount() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> toolCalls(new ToolCall("again-1", "again", "{}")));
        model.enqueue(prompt -> toolCalls(new ToolCall("again-2", "again", "{}")));
        Agent agent = Agent.builder("bounded-agent")
            .chatModel(model)
            .tool(tool("again", arguments -> "continue"))
            .executionPolicy(AgentExecutionPolicy.builder()
                .maxIterations(10)
                .maxSteps(2)
                .build())
            .build();

        AgentRun run = new AgentRunner().run(agent, "must stop");

        assertEquals(AgentRunStatus.MAX_STEPS_REACHED, run.getStatus());
        assertEquals(2, run.getStepCount());
        assertEquals(2, run.getIterationCount());
        assertEquals(2, run.getToolCallCount());
    }

    private AgentEvent find(List<AgentEvent> events, AgentEventType type) {
        for (AgentEvent event : events) {
            if (event.getType() == type) {
                return event;
            }
        }
        return null;
    }
}
