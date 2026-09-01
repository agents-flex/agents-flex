/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.compression.AgentCompressionFailureStrategy;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.prompt.MemoryPrompt;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentsflex.core.model.chat.tool.ToolExecutionTarget;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.toolgroup.ToolGroup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 新增 Agent 配置的行为级回归测试。
 */
public class AgentConfigurationOptimizationTest {

    @Test
    public void parallelToolCallsRunConcurrentlyAndPreserveResultOrder() throws Exception {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("a", "work_a", "{}"), new ToolCall("b", "work_b", "{}")));
        model.enqueue(prompt -> {
            List<Message> messages = prompt.getMessages();
            assertEquals("a", ((com.agentsflex.core.message.ToolMessage) messages.get(2)).getToolCallId());
            assertEquals("b", ((com.agentsflex.core.message.ToolMessage) messages.get(3)).getToolCallId());
            return new AiMessage("done");
        });
        CountDownLatch entered = new CountDownLatch(2);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        Agent agent = Agent.builder("parallel")
            .chatModel(model)
            .tool(AgentScenarioTestSupport.tool("work_a", args -> runConcurrent(entered, inFlight, maxInFlight)))
            .tool(AgentScenarioTestSupport.tool("work_b", args -> runConcurrent(entered, inFlight, maxInFlight)))
            .executionPolicy(AgentExecutionPolicy.builder()
                .toolExecutionMode(AgentToolExecutionMode.PARALLEL).build())
            .build();

        AgentTurn turn = new AgentRunner().run(agent, "run both");
        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertTrue("parallel mode did not overlap calls", maxInFlight.get() >= 2);
    }

    @Test
    public void toolVisibilityAlsoFiltersToolGroupButKeepsHiddenToolExecutable() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger hiddenInvocations = new AtomicInteger();
        model.enqueue(prompt -> {
            assertEquals(1, prompt.getToolGroups().size());
            assertEquals(1, prompt.getToolGroups().get(0).getTools().size());
            assertEquals("visible", prompt.getToolGroups().get(0).getTools().get(0).getName());
            // 模拟恢复或模型重放了一个当时不可见的 ToolCall，执行索引仍应能定位它。
            return AgentScenarioTestSupport.toolCalls(new ToolCall("hidden-call", "hidden", "{}"));
        });
        model.enqueue(prompt -> new AiMessage("done"));
        Tool hidden = AgentScenarioTestSupport.tool("hidden", args -> {
            hiddenInvocations.incrementAndGet();
            return "hidden-result";
        });
        Tool visible = AgentScenarioTestSupport.tool("visible", args -> "visible-result");
        Agent agent = Agent.builder("group-visibility")
            .chatModel(model)
            .toolGroup(ToolGroup.builder("group").addTools(Arrays.asList(visible, hidden)).build())
            .toolVisibilityPolicy((turn, tool) -> !"hidden".equals(tool.getName()))
            .build();

        AgentTurn turn = new AgentRunner().run(agent, "run hidden");
        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals(1, hiddenInvocations.get());
    }

    private static String runConcurrent(CountDownLatch entered, AtomicInteger inFlight,
                                        AtomicInteger maxInFlight) {
        int current = inFlight.incrementAndGet();
        for (; ; ) {
            int previous = maxInFlight.get();
            if (current <= previous || maxInFlight.compareAndSet(previous, current)) break;
        }
        entered.countDown();
        try {
            entered.await(2, TimeUnit.SECONDS);
            return "ok";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    @Test
    public void compressionUseOriginalHandlesCompressorFailureAndInvalidOutput() {
        MemoryPrompt source = new MemoryPrompt();
        source.addMessages(Arrays.<Message>asList(new com.agentsflex.core.message.UserMessage("old"),
            new AiMessage("answer"), new com.agentsflex.core.message.UserMessage("current"),
            new AiMessage("latest")));
        MemoryPrompt result = AgentContextWindow.build(source, 2, 100, 0, null,
            true, 1, messages -> {
                throw new IllegalStateException("compressor down");
            },
            AgentCompressionFailureStrategy.USE_ORIGINAL);
        assertEquals(4, result.getMemory().getMessages(Integer.MAX_VALUE).size());

        MemoryPrompt invalid = AgentContextWindow.build(source, 2, 100, 0, null,
            true, 1, messages -> Arrays.<Message>asList(new AiMessage("invalid")),
            AgentCompressionFailureStrategy.USE_ORIGINAL);
        assertEquals(4, invalid.getMemory().getMessages(Integer.MAX_VALUE).size());
    }

    @Test
    public void immediateCompressionIsCachedWithinOneTurn() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger compressions = new AtomicInteger();
        final List<String> compressionInputs = new java.util.ArrayList<>();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(new ToolCall("call", "echo", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("compression-cache")
            .chatModel(model)
            .tool(AgentScenarioTestSupport.tool("echo", args -> "ok"))
            .compressionPolicy(com.agentsflex.agent.compression.AgentContextCompressionPolicy.builder()
                .compressor(messages -> {
                    compressions.incrementAndGet();
                    compressionInputs.add(messages.get(0).getMessageId() + ":" + messages.get(0).getTextContent());
                    return Arrays.<Message>asList(new com.agentsflex.core.message.UserMessage("old summary"));
                })
                .keepRecentTurns(1)
                .build())
            .build();
        List<Message> history = Arrays.<Message>asList(
            new UserMessage("old question"), new AiMessage("old answer"));

        AgentTurn turn = new AgentRunner().run(agent, history, new UserMessage("current question"));
        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals("same Turn should reuse the immediate compression result " + compressionInputs,
            1, compressions.get());
    }

    @Test
    public void negativeTokenEstimateIsRejected() {
        MemoryPrompt source = new MemoryPrompt();
        source.addMessage(new com.agentsflex.core.message.UserMessage("hello"));
        try {
            AgentContextWindow.build(source, 1, 10, 1, messages -> -1,
                true, 1, null, AgentCompressionFailureStrategy.FAIL);
            fail("negative token estimates must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("non-negative"));
        }
    }

    @Test
    public void eventSanitizerFailureDoesNotBreakExecution() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("done"));
        AtomicInteger events = new AtomicInteger();
        AgentRunner runner = AgentRunner.builder()
            .options(AgentRunnerOptions.builder()
                .eventDataSanitizer((type, data) -> {
                    throw new IllegalStateException("bad sanitizer");
                })
                .build())
            .build()
            .addEventListener(event -> events.incrementAndGet());
        AgentTurn turn = runner.run(Agent.builder("sanitized").chatModel(model).build(), "hello");
        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertTrue(events.get() > 0);
    }

    @Test
    public void workerOptionsValidateAndExposePollingConfiguration() {
        AgentWorkerOptions options = AgentWorkerOptions.builder("worker-1", 5000)
            .pollIntervalMillis(250).batchSize(3).leaseRenewalFraction(0.25).build();
        assertEquals("worker-1", options.getWorkerId());
        assertEquals(5000, options.getLeaseMillis());
        assertEquals(250, options.getPollIntervalMillis());
        assertEquals(3, options.getBatchSize());
        assertEquals(0.25, options.getLeaseRenewalFraction(), 0.0001);
        try {
            AgentWorkerOptions.builder(" ", 1).build();
            fail("blank worker id must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("workerId"));
        }
    }

    @Test
    public void modelTimeoutUsesDefaultAsyncExecutor() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return new AiMessage("late");
        });
        Agent agent = Agent.builder("timeout")
            .chatModel(model)
            .executionPolicy(AgentExecutionPolicy.builder()
                .modelCallTimeoutMillis(20)
                .retryClassifier((turn, error, call) -> false)
                .build())
            .build();
        long started = System.currentTimeMillis();
        AgentTurn turn = new AgentRunner().run(agent, "timeout");
        assertEquals(AgentTurnStatus.FAILED, turn.getStatus());
        assertTrue("timeout did not return promptly", System.currentTimeMillis() - started < 250);
    }

    @Test
    public void externalToolResultSizeIsBounded() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(new ToolCall("external", "client", "{}")));
        Agent agent = Agent.builder("external-limit")
            .chatModel(model)
            .tool(com.agentsflex.core.model.chat.tool.Tool.builder("client", "client")
                .executionTarget(ToolExecutionTarget.EXTERNAL).build())
            .executionPolicy(AgentExecutionPolicy.builder()
                .externalToolResultMaxCharacters(3).build())
            .build();
        AgentRunner runner = new AgentRunner();
        AgentTurn waiting = runner.run(agent, "call client");
        try {
            runner.resume(waiting, AgentResumeCommand.toolResult("external", "tool-result"));
            fail("oversized external results must fail validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maximum size"));
        }
    }

    @Test
    public void externalToolResultExpiresAccordingToPolicy() throws Exception {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(new ToolCall("external", "client", "{}")));
        Agent agent = Agent.builder("external-timeout")
            .chatModel(model)
            .tool(com.agentsflex.core.model.chat.tool.Tool.builder("client", "client")
                .executionTarget(ToolExecutionTarget.EXTERNAL).build())
            .executionPolicy(AgentExecutionPolicy.builder()
                .externalToolTimeoutMillis(20).build())
            .build();
        AgentRunner runner = new AgentRunner();
        AgentTurn waiting = runner.run(agent, "call client");
        Thread.sleep(40);
        try {
            runner.resume(waiting, AgentResumeCommand.toolResult("external", "ok"));
            fail("expired external results must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("expired"));
        }
    }

    @Test
    public void streamingModelEntryPointAlsoHonorsTimeout() {
        Agent agent = Agent.builder("stream-timeout")
            .chatModel(new ChatModel() {
                @Override
                public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                    return AgentScenarioTestSupport.response(prompt, new AiMessage("unused"));
                }

                @Override
                public void chatStream(Prompt prompt, StreamResponseListener listener,
                                       ChatOptions options) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                }
            })
            .executionPolicy(AgentExecutionPolicy.builder()
                .modelCallTimeoutMillis(20)
                .retryClassifier((turn, error, call) -> false).build())
            .build();
        long started = System.currentTimeMillis();
        AgentTurn turn = new AgentRunner().run(agent, "stream", AgentTurnOptions.builder()
            .streaming(true).build());
        assertEquals(AgentTurnStatus.FAILED, turn.getStatus());
        assertTrue(System.currentTimeMillis() - started < 250);
    }
}
