/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 */
package com.agentsflex.agent;

import com.agentsflex.agent.command.AgentRunCommand;
import com.agentsflex.agent.command.AgentRunCommandStatus;
import com.agentsflex.agent.command.InMemoryAgentRunCommandStore;
import com.agentsflex.agent.context.InMemoryAgentArtifactStore;
import com.agentsflex.agent.context.MessageCountAgentContextManager;
import com.agentsflex.agent.context.ToolResultOffloadPolicy;
import com.agentsflex.agent.event.AgentRuntimeEvent;
import com.agentsflex.agent.event.AgentRuntimeEventType;
import com.agentsflex.agent.event.AgentRunEvent;
import com.agentsflex.agent.event.AgentRunEventStore;
import com.agentsflex.agent.event.InMemoryAgentRunEventStore;
import com.agentsflex.agent.middleware.AgentMiddleware;
import com.agentsflex.agent.middleware.AgentMiddlewareContext;
import com.agentsflex.agent.middleware.AgentModelCallChain;
import com.agentsflex.agent.middleware.AgentStepChain;
import com.agentsflex.agent.middleware.AgentToolCallChain;
import com.agentsflex.agent.middleware.AgentToolCallContext;
import com.agentsflex.agent.loader.InMemoryAgentLoader;
import com.agentsflex.agent.store.InMemoryAgentRunStore;
import com.agentsflex.agent.tool.AgentToolProgressEmitter;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.Message;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolContext;
import com.agentsflex.core.model.chat.tool.ToolContextHolder;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.Prompt;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 新增运行时能力的组合、隔离、恢复和并发场景测试。 */
public class AgentRuntimeCapabilitiesScenarioTest {

    @Test
    public void shouldPropagateInvocationContextWithoutPersistingIt() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(toolCalls(new ToolCall("call-1", "inspect", "{}")));
        model.enqueue(new AiMessage("done"));
        AtomicReference<String> middlewareTenant = new AtomicReference<>();
        AtomicReference<String> toolTenant = new AtomicReference<>();

        AgentMiddleware middleware = new AgentMiddleware() {
            @Override
            public AiMessageResponse aroundModelCall(AgentMiddlewareContext context,
                                                     AgentModelCallChain chain) {
                middlewareTenant.set(context.getInvocationContext().getTenantId());
                return chain.proceed(context);
            }
        };
        Tool tool = tool("inspect", args -> {
            ToolContext context = ToolContextHolder.currentContext();
            AgentInvocationContext invocation = context.getAttribute(
                AgentInvocationContext.CONTEXT_ATTRIBUTE);
            toolTenant.set(invocation.getTenantId());
            return "ok";
        });
        Agent agent = Agent.builder("context-agent")
            .chatModel(model)
            .middleware(middleware)
            .tool(tool)
            .build();
        AgentInvocationContext invocation = AgentInvocationContext.builder()
            .tenantId("tenant-a")
            .userId("user-a")
            .attribute(ClockService.class, new ClockService())
            .build();
        AgentRunner runner = new AgentRunner(
            new InMemoryAgentRunStore(), new InMemoryAgentLoader(agent));

        AgentRun run = runner.run(agent, "inspect",
            AgentRunOptions.builder().invocationContext(invocation).build());

        assertEquals("tenant-a", middlewareTenant.get());
        assertEquals("tenant-a", toolTenant.get());
        assertNotNull(run.getInvocationContext().get(ClockService.class));
        assertFalse(run.toSnapshot().getMetadata().containsKey("tenantId"));
        assertNull(runner.restore(run.getId()).getInvocationContext().getTenantId());
        assertEquals("tenant-b", runner.restore(run.getId(), AgentInvocationContext.builder()
            .tenantId("tenant-b").build()).getInvocationContext().getTenantId());
    }

    @Test
    public void shouldApplyMiddlewareInOnionOrder() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(toolCalls(new ToolCall("call-1", "work", "{}")));
        model.enqueue(new AiMessage("done"));
        List<String> calls = new ArrayList<>();

        Agent agent = Agent.builder("middleware-agent")
            .chatModel(model)
            .middleware(recordingMiddleware("a", calls))
            .middleware(recordingMiddleware("b", calls))
            .tool(tool("work", args -> "ok"))
            .build();

        AgentRun run = new AgentRunner().run(agent, "run");

        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertTrue(calls.indexOf("a-step-before") < calls.indexOf("b-step-before"));
        assertTrue(calls.indexOf("b-step-after") < calls.indexOf("a-step-after"));
        assertTrue(calls.indexOf("a-model-before") < calls.indexOf("b-model-before"));
        assertTrue(calls.indexOf("b-model-after") < calls.indexOf("a-model-after"));
        assertTrue(calls.indexOf("a-tool-before") < calls.indexOf("b-tool-before"));
        assertTrue(calls.indexOf("b-tool-after") < calls.indexOf("a-tool-after"));
    }

    @Test
    public void shouldIsolateInvocationContextsAcrossConcurrentRuns() throws Exception {
        Map<String, String> tenantsByRequest = new ConcurrentHashMap<>();
        AgentMiddleware middleware = new AgentMiddleware() {
            @Override
            public AiMessageResponse aroundModelCall(AgentMiddlewareContext context,
                                                     AgentModelCallChain chain) {
                AgentInvocationContext invocation = context.getInvocationContext();
                tenantsByRequest.put(invocation.getRequestId(), invocation.getTenantId());
                return chain.proceed(context);
            }
        };
        Agent agent = Agent.builder("concurrent-context-agent")
            .chatModel(new ImmediateChatModel())
            .middleware(middleware)
            .build();
        AgentRunner runner = new AgentRunner();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> runner.run(agent, "a", AgentRunOptions.builder()
            .invocationContext(AgentInvocationContext.builder()
                .tenantId("tenant-a").requestId("request-a").build()).build()));
        executor.submit(() -> runner.run(agent, "b", AgentRunOptions.builder()
            .invocationContext(AgentInvocationContext.builder()
                .tenantId("tenant-b").requestId("request-b").build()).build()));
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals("tenant-a", tenantsByRequest.get("request-a"));
        assertEquals("tenant-b", tenantsByRequest.get("request-b"));
    }

    @Test
    public void shouldAllowModelMiddlewareToShortCircuit() {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel model = new ImmediateChatModel() {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                modelCalls.incrementAndGet();
                return super.chat(prompt, options);
            }
        };
        AgentMiddleware middleware = new AgentMiddleware() {
            @Override
            public AiMessageResponse aroundModelCall(AgentMiddlewareContext context,
                                                     AgentModelCallChain chain) {
                return response(context.getPrompt(), new AiMessage("cached"));
            }
        };
        Agent agent = Agent.builder("cached-agent")
            .chatModel(model)
            .middleware(middleware)
            .build();

        AgentRun run = new AgentRunner().run(agent, "question");

        assertEquals("cached", run.getFinalOutput());
        assertEquals(0, modelCalls.get());
    }

    @Test
    public void shouldPropagateStepMiddlewareErrorToCaller() {
        Agent agent = Agent.builder("middleware-error-agent")
            .chatModel(new ImmediateChatModel())
            .middleware(new AgentMiddleware() {
                @Override
                public AgentStepResult aroundStep(AgentMiddlewareContext context,
                                                  AgentStepChain chain) {
                    throw new RuntimeException("middleware failed");
                }
            })
            .build();

        AgentRunner runner = new AgentRunner();
        AgentRun run = runner.start(agent, "question");

        RuntimeException error = assertThrows(RuntimeException.class,
            () -> runner.step(run));

        assertEquals("middleware failed", error.getMessage());
    }

    @Test
    public void shouldRebuildInvocationContextWhenWorkerRestoresRun() {
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AtomicReference<String> tenant = new AtomicReference<>();
        Agent agent = Agent.builder("worker-context-agent")
            .chatModel(new ImmediateChatModel())
            .middleware(new AgentMiddleware() {
                @Override
                public AiMessageResponse aroundModelCall(AgentMiddlewareContext context,
                                                         AgentModelCallChain chain) {
                    tenant.set(context.getInvocationContext().getTenantId());
                    return chain.proceed(context);
                }
            })
            .build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner runner = new AgentRunner(runStore, registry);
        runner.start(agent, "work");
        AgentWorker worker = new AgentWorker("worker-context", runner, 10_000,
            snapshot -> AgentInvocationContext.builder().tenantId("tenant-worker").build());

        List<AgentRun> completed = worker.pollAndRun(1);

        assertEquals(1, completed.size());
        assertEquals("tenant-worker", tenant.get());
    }

    @Test
    public void shouldCombineStreamingApprovalInboxWorkerProgressAndOffload() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(toolCalls(new ToolCall("danger-1", "export", "{}")));
        model.enqueue(new AiMessage("completed"));
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        InMemoryAgentRunCommandStore commandStore = new InMemoryAgentRunCommandStore();
        InMemoryAgentArtifactStore artifactStore = new InMemoryAgentArtifactStore();
        List<AgentRuntimeEvent> events = new ArrayList<>();
        AtomicInteger toolExecutions = new AtomicInteger();

        Tool export = tool("export", args -> {
            AgentToolProgressEmitter progress = ToolContextHolder.currentContext()
                .getAttribute(AgentToolProgressEmitter.CONTEXT_ATTRIBUTE);
            progress.emit("half", java.util.Collections.singletonMap("percent", 50));
            toolExecutions.incrementAndGet();
            char[] content = new char[128];
            Arrays.fill(content, 'x');
            return new String(content);
        });
        Agent agent = Agent.builder("durable-agent")
            .chatModel(model)
            .tool(export)
            .toolApprovalPolicy((run, call, tool) -> ToolApprovalDecision.requireApproval()
                .code("EXPORT_REVIEW")
                .message("Export requires review")
                .reason("large data export")
                .metadata("risk", "high")
                .build())
            .toolResultOffloadPolicy(ToolResultOffloadPolicy.largerThan(32))
            .build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner runner = new AgentRunner(runStore, registry,
            new InMemoryAgentRunEventStore(),
            commandStore, artifactStore).addRuntimeEventListener(events::add);

        AgentRun waiting = runner.run(agent, "export",
            AgentRunOptions.builder().invocationContext(AgentInvocationContext.builder()
                .streaming(true).requestId("request-1").build()).build());

        assertEquals(AgentRunStatus.WAITING_FOR_APPROVAL, waiting.getStatus());
        assertEquals("EXPORT_REVIEW", waiting.getSuspension().getMetadata().get("approvalCode"));
        assertEquals("high", waiting.getSuspension().getMetadata().get("risk"));
        AgentResumeCommand approval = AgentResumeCommand.approveTool("danger-1")
            .withMetadata("reviewer", "alice");
        AgentRunCommand command = runner.submitCommand(
            "approve-1", waiting.getId(), approval);
        AgentRunCommand duplicate = runner.submitCommand(
            "approve-1", waiting.getId(), approval);
        assertEquals(command.getCreatedAt(), duplicate.getCreatedAt());
        try {
            runner.submitCommand("approve-1", waiting.getId(),
                AgentResumeCommand.rejectTool("danger-1", "denied"));
            throw new AssertionError("reusing commandId for another decision must fail");
        } catch (IllegalArgumentException expected) {
            // 同一个幂等键不能代表两个不同的审批决定。
        }

        List<AgentRun> completed = new AgentWorker("worker-1", runner, 30_000).pollAndRun(10);

        assertEquals(1, completed.size());
        assertEquals(AgentRunStatus.COMPLETED, completed.get(0).getStatus());
        assertEquals(1, toolExecutions.get());
        assertEquals(AgentRunCommandStatus.COMPLETED,
            commandStore.load("approve-1").getStatus());
        ToolMessage toolMessage = lastToolMessage(completed.get(0));
        String artifactId = (String) toolMessage.getMetadata("agent.artifact.id");
        assertNotNull(artifactId);
        assertEquals(128, artifactStore.load(artifactId).length());
        assertTrue(hasEvent(events, AgentRuntimeEventType.MODEL_REASONING_DELTA));
        assertTrue(hasEvent(events, AgentRuntimeEventType.MODEL_TOOL_CALL_DELTA));
        assertTrue(hasEvent(events, AgentRuntimeEventType.TOOL_PROGRESS));
        assertTrue(hasEvent(events, AgentRuntimeEventType.TOOL_RESULT_OFFLOADED));
        assertTrue(hasEvent(events, AgentRuntimeEventType.COMMAND_CONSUMED));
        assertEquals(1, terminalEventCount(events));
        assertStrictSequences(events);
    }

    @Test
    public void shouldReturnStructuredApprovalAuditWhenUserRejectsTool() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(toolCalls(new ToolCall("delete-1", "delete", "{}")));
        model.enqueue(new AiMessage("cancelled"));
        Agent agent = Agent.builder("structured-rejection-agent")
            .chatModel(model)
            .tool(tool("delete", args -> {
                throw new AssertionError("rejected tool must not execute");
            }))
            .toolApprovalPolicy((run, call, tool) -> ToolApprovalDecision.requireApproval()
                .code("DELETE_REVIEW")
                .reason("destructive operation")
                .metadata("risk", "critical")
                .build())
            .build();
        AgentRunner runner = new AgentRunner();
        AgentRun waiting = runner.run(agent, "delete");

        AgentRun completed = runner.resume(waiting,
            AgentResumeCommand.rejectTool("delete-1", "not approved"));

        ToolMessage rejected = lastToolMessage(completed);
        assertTrue(rejected.getContent().contains("DELETE_REVIEW"));
        assertTrue(rejected.getContent().contains("not approved"));
        assertTrue(rejected.getContent().contains("critical"));
    }

    @Test(timeout = 3000)
    public void shouldFinishWhenStreamingFailsBeforeOpen() {
        ChatModel model = new ImmediateChatModel() {
            @Override
            public void chatStream(Prompt prompt, StreamResponseListener listener,
                                   ChatOptions options) {
                listener.onError(null, new RuntimeException("connection failed"));
            }
        };
        Agent agent = Agent.builder("stream-error-agent").chatModel(model).build();

        AgentRun run = new AgentRunner().run(agent, "question",
            AgentRunOptions.builder().invocationContext(AgentInvocationContext.builder()
                .streaming(true).build()).build());

        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertTrue(run.getError().getMessage().contains("connection failed"));
    }

    @Test
    public void shouldCompactOldHistoryAndKeepRecentMessages() {
        ScriptedChatModel model = new ScriptedChatModel();
        model.enqueue(new AiMessage("done"));
        Agent agent = Agent.builder("compact-agent")
            .chatModel(model)
            .contextManager(new MessageCountAgentContextManager(5, 3,
                (messages, context) -> "summarized " + messages.size() + " messages"))
            .build();
        AgentRun run = AgentRun.start(agent, "m1");
        for (int i = 2; i <= 8; i++) run.getPrompt().addUserMessage("m" + i);
        List<AgentRuntimeEvent> events = new ArrayList<>();

        new AgentRunner().addRuntimeEventListener(events::add).run(run);

        List<Message> messages = run.getPrompt().getMemory().getMessages(Integer.MAX_VALUE);
        assertEquals("Conversation summary:\nsummarized 5 messages", messages.get(0).getTextContent());
        assertEquals("m6", messages.get(1).getTextContent());
        assertEquals("m8", messages.get(3).getTextContent());
        assertEquals("done", messages.get(4).getTextContent());
        assertTrue(hasEvent(events, AgentRuntimeEventType.CONTEXT_COMPACTED));
    }

    @Test
    public void shouldPreserveToolProtocolBoundaryWhenCompacting() {
        ChatModel model = new ImmediateChatModel() {
            @Override
            public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
                List<Message> messages = prompt.getMessages();
                assertTrue(messages.get(1) instanceof AiMessage);
                assertTrue(((AiMessage) messages.get(1)).hasToolCalls());
                assertTrue(messages.get(2) instanceof ToolMessage);
                assertEquals("call-1", ((ToolMessage) messages.get(2)).getToolCallId());
                return response(prompt, new AiMessage("done"));
            }
        };
        Agent agent = Agent.builder("protocol-agent")
            .chatModel(model)
            .contextManager(new MessageCountAgentContextManager(5, 2,
                (messages, context) -> "older history"))
            .build();
        AgentRun run = AgentRun.start(agent, "u1");
        run.getPrompt().addUserMessage("u2");
        run.getPrompt().addUserMessage("u3");
        run.getPrompt().addMessage(toolCalls(new ToolCall("call-1", "lookup", "{}")));
        ToolMessage result = new ToolMessage();
        result.setToolCallId("call-1");
        result.setContent("value");
        run.getPrompt().addMessage(result);
        run.getPrompt().addUserMessage("continue");

        new AgentRunner().run(run);

        List<Message> messages = run.getPrompt().getMemory().getMessages(Integer.MAX_VALUE);
        assertTrue(messages.get(1) instanceof AiMessage);
        assertTrue(messages.get(2) instanceof ToolMessage);
    }

    @Test
    public void shouldClaimEachInboxCommandOnlyOnceAcrossWorkers() throws Exception {
        InMemoryAgentRunCommandStore store = new InMemoryAgentRunCommandStore();
        store.submit(AgentRunCommand.pending("c1", "run-1",
            AgentResumeCommand.continueRun()));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();

        executor.submit(() -> claim(store, "w1", start, claimed));
        executor.submit(() -> claim(store, "w2", start, claimed));
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, claimed.get());
    }

    @Test
    public void shouldAcknowledgeRedeliveredCommandWithoutApplyingItTwice() {
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        InMemoryAgentRunCommandStore commandStore = new InMemoryAgentRunCommandStore();
        FailOnceEventStore eventStore = new FailOnceEventStore();
        Agent agent = Agent.builder("command-redelivery-agent")
            .chatModel(new ImmediateChatModel())
            .build();
        InMemoryAgentLoader registry = new InMemoryAgentLoader(agent);
        AgentRunner runner = new AgentRunner(runStore, registry,
            eventStore, commandStore,
            new InMemoryAgentArtifactStore());
        AgentRun waiting = runner.start(agent, "start");
        runner.suspend(waiting, AgentSuspension.userInput("provide value"));
        runner.submitCommand("input-1", waiting.getId(),
            AgentResumeCommand.userInput("reply"));
        eventStore.failNextAppend = true;

        assertEquals(0, runner.processCommands("worker", 10_000, 1));
        assertEquals(AgentRunCommandStatus.PENDING,
            commandStore.load("input-1").getStatus());
        assertEquals(1, runner.processCommands("worker", 10_000, 1));
        assertEquals(AgentRunCommandStatus.COMPLETED,
            commandStore.load("input-1").getStatus());

        int replyCount = 0;
        for (Message message : runner.restore(waiting.getId()).getPrompt()
            .getMemory().getMessages(Integer.MAX_VALUE)) {
            if ("reply".equals(message.getTextContent())) replyCount++;
        }
        assertEquals(1, replyCount);
    }

    private static void claim(InMemoryAgentRunCommandStore store, String worker,
                              CountDownLatch start, AtomicInteger claimed) {
        try {
            start.await();
            claimed.addAndGet(store.claim(worker, System.currentTimeMillis(), 10_000, 1).size());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static AgentMiddleware recordingMiddleware(String name, List<String> calls) {
        return new AgentMiddleware() {
            @Override
            public AgentStepResult aroundStep(AgentMiddlewareContext context, AgentStepChain chain) {
                calls.add(name + "-step-before");
                AgentStepResult result = chain.proceed(context);
                calls.add(name + "-step-after");
                return result;
            }

            @Override
            public AiMessageResponse aroundModelCall(AgentMiddlewareContext context,
                                                     AgentModelCallChain chain) {
                calls.add(name + "-model-before");
                AiMessageResponse result = chain.proceed(context);
                calls.add(name + "-model-after");
                return result;
            }

            @Override
            public Object aroundToolCall(AgentToolCallContext context, AgentToolCallChain chain) {
                calls.add(name + "-tool-before");
                Object result = chain.proceed(context);
                calls.add(name + "-tool-after");
                return result;
            }
        };
    }

    private static boolean hasEvent(List<AgentRuntimeEvent> events, AgentRuntimeEventType type) {
        for (AgentRuntimeEvent event : events) if (event.getType() == type) return true;
        return false;
    }

    private static int terminalEventCount(List<AgentRuntimeEvent> events) {
        int count = 0;
        for (AgentRuntimeEvent event : events) {
            if (event.getType() == AgentRuntimeEventType.RUN_COMPLETED
                || event.getType() == AgentRuntimeEventType.RUN_FAILED
                || event.getType() == AgentRuntimeEventType.RUN_CANCELLED
                || event.getType() == AgentRuntimeEventType.BUDGET_EXCEEDED) count++;
        }
        return count;
    }

    private static void assertStrictSequences(List<AgentRuntimeEvent> events) {
        long previous = 0;
        for (AgentRuntimeEvent event : events) {
            assertTrue(event.getSequence() > previous);
            previous = event.getSequence();
        }
    }

    private static ToolMessage lastToolMessage(AgentRun run) {
        List<Message> messages = run.getPrompt().getMemory().getMessages(Integer.MAX_VALUE);
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ToolMessage) return (ToolMessage) messages.get(i);
        }
        return null;
    }

    private static Tool tool(String name, java.util.function.Function<Map<String, Object>, Object> fn) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Parameter[] getParameters() { return new Parameter[0]; }
            @Override public Object invoke(Map<String, Object> argsMap) { return fn.apply(argsMap); }
        };
    }

    private static AiMessage toolCalls(ToolCall... calls) {
        AiMessage message = new AiMessage();
        message.setToolCalls(Arrays.asList(calls));
        return message;
    }

    private static AiMessageResponse response(Prompt prompt, AiMessage message) {
        ChatContext context = new ChatContext();
        context.setPrompt(prompt);
        return new AiMessageResponse(context, null, message);
    }

    private static final class ClockService { }

    /** 同时支持同步和流式调用的确定性脚本模型。 */
    private static class ImmediateChatModel implements ChatModel {
        @Override
        public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
            return response(prompt, new AiMessage("done"));
        }

        @Override
        public void chatStream(Prompt prompt, StreamResponseListener listener,
                               ChatOptions options) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final Deque<AiMessage> responses = new ArrayDeque<>();

        void enqueue(AiMessage message) { responses.add(message); }

        @Override
        public AiMessageResponse chat(Prompt prompt, ChatOptions options) {
            return response(prompt, responses.removeFirst());
        }

        @Override
        public void chatStream(Prompt prompt, StreamResponseListener listener,
                               ChatOptions options) {
            AiMessage full = responses.removeFirst();
            ChatContext chatContext = new ChatContext();
            chatContext.setPrompt(prompt);
            StreamContext context = new StreamContext(this, chatContext, null);
            listener.onOpen(context);
            AiMessage reasoning = new AiMessage();
            reasoning.setReasoningContent("checking");
            listener.onMessage(context, response(prompt, reasoning));
            if (full.hasToolCalls()) {
                AiMessage toolDelta = new AiMessage();
                toolDelta.setToolCalls(AgentMessageUtils.copyToolCalls(full.getToolCalls()));
                listener.onMessage(context, response(prompt, toolDelta));
            } else {
                AiMessage text = new AiMessage(full.getContent());
                listener.onMessage(context, response(prompt, text));
            }
            full.setFinished(true);
            context.setFullMessage(full);
            listener.onMessage(context, response(prompt, full));
            listener.onClose(context);
        }
    }

    /** 在指定的一次 append 上模拟事件系统故障。 */
    private static final class FailOnceEventStore implements AgentRunEventStore {
        private final InMemoryAgentRunEventStore delegate = new InMemoryAgentRunEventStore();
        private boolean failNextAppend;

        @Override
        public AgentRunEvent append(AgentRunEvent event) {
            if (failNextAppend) {
                failNextAppend = false;
                throw new RuntimeException("simulated event failure after checkpoint");
            }
            return delegate.append(event);
        }

        @Override
        public List<AgentRunEvent> load(String runId, long afterSequence, int limit) {
            return delegate.load(runId, afterSequence, limit);
        }
    }
}
