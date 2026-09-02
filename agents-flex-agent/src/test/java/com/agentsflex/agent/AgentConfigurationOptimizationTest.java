/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.compression.AgentCompressionFailureStrategy;
import com.agentsflex.agent.event.AgentEvent;
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
import java.util.concurrent.Executor;
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
    public void parallelFailurePersistsSuccessfulSiblingOnlyOnce() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger successfulCalls = new AtomicInteger();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("failed", "fail", "{}"), new ToolCall("ok", "ok", "{}")));
        Agent agent = Agent.builder("parallel-failure")
            .chatModel(model)
            .tool(AgentScenarioTestSupport.tool("fail", args -> {
                throw new IllegalStateException("boom");
            }))
            .tool(AgentScenarioTestSupport.tool("ok", args -> {
                successfulCalls.incrementAndGet();
                return "ok";
            }))
            .executionPolicy(AgentExecutionPolicy.builder()
                .toolExecutionMode(AgentToolExecutionMode.PARALLEL)
                .retryPolicy(AgentRetryPolicy.builder().maxRetries(1).initialDelayMillis(0).build())
                .build())
            .build();

        AgentTurn turn = new AgentRunner().run(agent, "parallel failure");
        assertEquals(AgentTurnStatus.RETRY_SCHEDULED, turn.getStatus());
        assertEquals("successful sibling must not be retried", 1, successfulCalls.get());
        assertEquals("only the failed call remains pending", 1, turn.getPendingToolCalls().size());
        assertEquals("failed", turn.getPendingToolCalls().get(0).getId());
    }

    @Test
    public void parallelFailureCanReturnStructuredErrorsToModel() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("failed", "fail", "{}"), new ToolCall("ok", "ok", "{}")));
        model.enqueue(prompt -> {
            assertEquals(4, prompt.getMessages().size());
            assertTrue(prompt.getMessages().get(2).getTextContent().contains("error"));
            assertEquals("ok", prompt.getMessages().get(3).getTextContent());
            return new AiMessage("recovered");
        });
        Agent agent = Agent.builder("parallel-errors")
            .chatModel(model)
            .tool(AgentScenarioTestSupport.tool("fail", args -> {
                throw new IllegalStateException("boom");
            }))
            .tool(AgentScenarioTestSupport.tool("ok", args -> "ok"))
            .executionPolicy(AgentExecutionPolicy.builder()
                .toolExecutionMode(AgentToolExecutionMode.PARALLEL)
                .parallelFailureStrategy(AgentParallelFailureStrategy.RETURN_ERRORS_TO_MODEL)
                .build())
            .build();

        AgentTurn turn = new AgentRunner().run(agent, "parallel errors");
        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertTrue(turn.getPendingToolCalls().isEmpty());
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
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(new ToolCall("call", "echo", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("compression-cache")
            .chatModel(model)
            .tool(AgentScenarioTestSupport.tool("echo", args -> "ok"))
            .compressionPolicy(com.agentsflex.agent.compression.AgentContextCompressionPolicy.builder()
                .compressor(messages -> {
                    compressions.incrementAndGet();
                    return Arrays.<Message>asList(new com.agentsflex.core.message.UserMessage("old summary"));
                })
                .keepRecentTurns(1)
                .build())
            .build();
        List<Message> history = Arrays.<Message>asList(
            new UserMessage("old question"), new AiMessage("old answer"));

        AgentTurn turn = new AgentRunner().run(agent, history, new UserMessage("current question"));
        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals("same Turn should reuse the immediate compression result", 1, compressions.get());
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
    public void eventSanitizerFailureStrategiesHaveExplicitDataSemantics() {
        for (AgentEventSanitizationFailureStrategy strategy : AgentEventSanitizationFailureStrategy.values()) {
            AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
            model.enqueue(prompt -> new AiMessage("done"));
            List<AgentEvent> received = new java.util.ArrayList<>();
            AgentRunner runner = AgentRunner.builder()
                .options(AgentRunnerOptions.builder()
                    .eventDataSanitizer((type, data) -> {
                        throw new IllegalStateException("sanitize");
                    })
                    .eventSanitizationFailureStrategy(strategy).build())
                .build().addEventListener(received::add);
            try {
                AgentTurn turn = runner.run(Agent.builder("sanitize-" + strategy).chatModel(model).build(), "hello");
                assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
            } catch (IllegalStateException expected) {
                assertEquals(AgentEventSanitizationFailureStrategy.FAIL_EXECUTION, strategy);
            }
            if (strategy == AgentEventSanitizationFailureStrategy.DROP_EVENT) {
                assertTrue(received.isEmpty());
            } else if (strategy == AgentEventSanitizationFailureStrategy.DROP_DATA) {
                assertTrue(received.size() > 0);
                assertTrue(received.get(0).getData().isEmpty());
            } else if (strategy == AgentEventSanitizationFailureStrategy.USE_ORIGINAL) {
                assertTrue(received.size() > 0);
                assertTrue(received.get(0).getData().containsKey("status"));
            }
        }
    }

    @Test
    public void workerOptionsValidateAndExposePollingConfiguration() {
        AgentWorkerOptions options = AgentWorkerOptions.builder("worker-1", 5000)
            .pollIntervalMillis(250).batchSize(3).leaseRenewalFraction(0.25).build();
        assertEquals("worker-1", options.getWorkerId());
        assertEquals(5000, options.getLeaseMillis());
        assertEquals(250, options.getPollIntervalMillis());
        assertEquals(3, options.getBatchSize());
        assertEquals(1, options.getMaxConcurrentTurns());
        assertEquals(0.25, options.getLeaseRenewalFraction(), 0.0001);
        try {
            AgentWorkerOptions.builder(" ", 1).build();
            fail("blank worker id must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("workerId"));
        }
    }

    @Test
    public void parallelToolCallLimitIsEnforcedWhileUsingConfiguredToolExecutor() throws Exception {
        for (int limit : new int[]{1, 2}) {
            AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
            model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
                new ToolCall("a", "a", "{}"), new ToolCall("b", "b", "{}"),
                new ToolCall("c", "c", "{}")));
            model.enqueue(prompt -> new AiMessage("done"));
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger current = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            AtomicInteger executorSubmissions = new AtomicInteger();
            Executor toolExecutor = command -> {
                executorSubmissions.incrementAndGet();
                Thread thread = new Thread(command, "test-tool");
                thread.start();
            };
            Agent agent = Agent.builder("parallel-limit-" + limit)
                .chatModel(model)
                .tool(AgentScenarioTestSupport.tool("a", args -> limitedTool(release, current, maximum, limit)))
                .tool(AgentScenarioTestSupport.tool("b", args -> limitedTool(release, current, maximum, limit)))
                .tool(AgentScenarioTestSupport.tool("c", args -> limitedTool(release, current, maximum, limit)))
                .executionPolicy(AgentExecutionPolicy.builder()
                    .toolExecutionMode(AgentToolExecutionMode.PARALLEL)
                    .maxParallelToolCalls(limit).build())
                .build();
            AgentRunner runner = AgentRunner.builder()
                .options(AgentRunnerOptions.builder().toolExecutor(toolExecutor).build()).build();
            AgentTurn turn = runner.run(agent, "limited");
            release.countDown();
            assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
            assertTrue("configured executor was not used", executorSubmissions.get() >= 3);
            assertTrue("parallel limit exceeded: " + maximum.get(), maximum.get() <= limit);
        }
    }

    @Test
    public void workerBatchSizeAndConcurrencyAreIndependent() throws Exception {
        com.agentsflex.agent.store.InMemoryAgentTurnStore store =
            new com.agentsflex.agent.store.InMemoryAgentTurnStore();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                int now = active.incrementAndGet();
                maximum.updateAndGet(previous -> Math.max(previous, now));
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                }
                return AgentScenarioTestSupport.response(prompt, new AiMessage("done"));
            }

            @Override
            public void chatStream(Prompt prompt, StreamResponseListener listener, ChatOptions options) {
                throw new UnsupportedOperationException();
            }
        };
        Agent agent = Agent.builder("worker-concurrency").chatModel(model).build();
        AgentRunner runner = new AgentRunner(store, new com.agentsflex.agent.loader.InMemoryAgentLoader(agent));
        runner.start(agent, "first");
        runner.start(agent, "second");
        AgentWorkerOptions options = AgentWorkerOptions.builder("worker", 10_000)
            .batchSize(1).maxConcurrentTurns(2).build();
        try (AgentWorker worker = new AgentWorker(runner, options)) {
            Thread polling = new Thread(() -> worker.pollAndRun(2));
            polling.start();
            assertTrue("worker did not execute two turns concurrently", entered.await(2, TimeUnit.SECONDS));
            release.countDown();
            polling.join(2000);
            assertEquals(2, maximum.get());
        }
    }

    private static String limitedTool(CountDownLatch release, AtomicInteger current,
                                      AtomicInteger maximum, int limit) {
        int now = current.incrementAndGet();
        maximum.updateAndGet(previous -> Math.max(previous, now));
        if (now >= limit) release.countDown();
        try {
            release.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            current.decrementAndGet();
        }
        return "ok";
    }

    @Test
    public void parallelToolCallLimitFallsBackToSequentialExecution() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger calls = new AtomicInteger();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(
            new ToolCall("a", "a", "{}"), new ToolCall("b", "b", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("parallel-limit")
            .chatModel(model)
            .tool(AgentScenarioTestSupport.tool("a", args -> {
                calls.incrementAndGet();
                return "a";
            }))
            .tool(AgentScenarioTestSupport.tool("b", args -> {
                calls.incrementAndGet();
                return "b";
            }))
            .executionPolicy(AgentExecutionPolicy.builder()
                .toolExecutionMode(AgentToolExecutionMode.PARALLEL)
                .maxParallelToolCalls(1).build())
            .build();
        AgentTurn turn = new AgentRunner().run(agent, "limit");
        assertEquals(AgentTurnStatus.COMPLETED, turn.getStatus());
        assertEquals(2, calls.get());
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
                .retryDecider((turn, error, call) -> false)
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
    public void oversizedLocalToolResultCanBeTruncatedWithExplicitMarker() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(new ToolCall("local", "echo", "{}")));
        model.enqueue(prompt -> {
            assertEquals("ab\n...[tool result truncated]",
                ((com.agentsflex.core.message.ToolMessage) prompt.getMessages().get(2)).getContent());
            return new AiMessage("done");
        });
        Agent agent = Agent.builder("truncate-local")
            .chatModel(model)
            .tool(AgentScenarioTestSupport.tool("echo", args -> "abcdefghijklmnopqrstuvwxyz1234567890"))
            .executionPolicy(AgentExecutionPolicy.builder()
                .toolResultMaxCharacters(29)
                .toolResultOverflowStrategy(AgentToolResultOverflowStrategy.TRUNCATE)
                .build())
            .build();
        assertEquals(AgentTurnStatus.COMPLETED, new AgentRunner().run(agent, "truncate").getStatus());
    }

    @Test
    public void toolResultExactlyAtConfiguredLimitIsAccepted() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(new ToolCall("local", "echo", "{}")));
        model.enqueue(prompt -> new AiMessage("done"));
        Agent agent = Agent.builder("exact-result-limit")
            .chatModel(model)
            .tool(AgentScenarioTestSupport.tool("echo", args -> "12345"))
            .executionPolicy(AgentExecutionPolicy.builder().toolResultMaxCharacters(5).build())
            .build();
        assertEquals(AgentTurnStatus.COMPLETED, new AgentRunner().run(agent, "exact").getStatus());
    }

    @Test
    public void oversizedExternalToolResultCanBeTruncatedWithoutChangingCommandType() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> AgentScenarioTestSupport.toolCalls(new ToolCall("external", "client", "{}")));
        model.enqueue(prompt -> {
            assertEquals("ab\n...[tool result truncated]",
                ((com.agentsflex.core.message.ToolMessage) prompt.getMessages().get(2)).getContent());
            return new AiMessage("done");
        });
        Agent agent = Agent.builder("truncate-external")
            .chatModel(model)
            .tool(com.agentsflex.core.model.chat.tool.Tool.builder("client", "client")
                .executionTarget(ToolExecutionTarget.EXTERNAL).build())
            .executionPolicy(AgentExecutionPolicy.builder()
                .externalToolResultMaxCharacters(29)
                .toolResultOverflowStrategy(AgentToolResultOverflowStrategy.TRUNCATE)
                .build())
            .build();
        AgentRunner runner = new AgentRunner();
        AgentTurn waiting = runner.run(agent, "truncate external");
        assertEquals(AgentTurnStatus.COMPLETED,
            runner.resume(waiting, new AgentResumeCommand(AgentResumeCommandType.TOOL_ERROR,
                "abcdefghijklmnopqrstuvwxyz1234567890", "external", null)).getStatus());
    }

    @Test
    public void retryPolicyRejectsNonFiniteMultiplier() {
        try {
            AgentRetryPolicy.builder().multiplier(Double.NaN).build();
            fail("NaN multiplier must fail validation");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("retry"));
        }
        try {
            AgentRetryPolicy.builder().multiplier(Double.POSITIVE_INFINITY).build();
            fail("infinite multiplier must fail validation");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("retry"));
        }
    }

    @Test
    public void workerRejectsNonPositivePollLimit() {
        AgentWorker worker = new AgentWorker("limit-worker", new AgentRunner(), 1000);
        try {
            worker.pollAndRun(0);
            fail("zero poll limit must fail validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("limit"));
        } finally {
            worker.close();
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
                .retryDecider((turn, error, call) -> false).build())
            .build();
        long started = System.currentTimeMillis();
        AgentTurn turn = new AgentRunner().run(agent, "stream", AgentTurnOptions.builder()
            .streaming(true).build());
        assertEquals(AgentTurnStatus.FAILED, turn.getStatus());
        assertTrue(System.currentTimeMillis() - started < 250);
    }
}
