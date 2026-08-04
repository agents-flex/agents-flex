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
import com.agentsflex.agent.store.FastjsonAgentStoreSerializer;
import com.agentsflex.agent.task.AgentPlanningPolicy;
import com.agentsflex.agent.task.AgentPlanningTool;
import com.agentsflex.agent.task.AgentTask;
import com.agentsflex.agent.task.AgentTaskPlanStatus;
import com.agentsflex.agent.task.AgentTaskProgress;
import com.agentsflex.agent.task.AgentTaskStatus;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.UserMessage;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.Parameter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.agent.AgentScenarioTestSupport.tool;
import static com.agentsflex.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 验证任务规划作为 AgentRunner 内置能力时的组合场景。 */
public class AgentTaskPlanningScenarioTest {

    @Test
    public void shouldLetModelDecideWhetherPlanningIsNeeded() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            assertTrue(hasTool(prompt.getTools(), AgentPlanningTool.NAME));
            return new AiMessage("你好，有什么可以帮你？");
        });
        Agent agent = planningAgent("decision-agent", model);

        AgentRun run = runner(agent).run(agent, "你好");

        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertEquals("你好，有什么可以帮你？", run.getFinalOutput());
        assertNull(run.getTaskPlan());
        assertEquals(1, model.getCallCount());
    }

    @Test
    public void shouldCreateExecuteAndPersistPlanInsideRootRun() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("完成变更",
            taskJson("inspect", "分析代码", "定位问题", null) + ","
                + taskJson("test", "运行测试", "验证修改", null)));
        model.enqueue(prompt -> new AiMessage("分析结果"));
        model.enqueue(prompt -> new AiMessage("测试结果"));
        model.enqueue(prompt -> {
            long childResults = prompt.getMessages().stream()
                .filter(message -> message.getTextContent() != null
                    && message.getTextContent().contains("Child Agent result:"))
                .count();
            assertEquals(2, childResults);
            return new AiMessage("最终答案");
        });
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        Agent agent = planningAgent("sequential-agent", model);
        AgentRunner runner = new AgentRunner(store, new InMemoryAgentLoader(agent));

        AgentRun result = runner.run(agent, "请完成这项复杂变更");
        AgentRun restored = runner.restore(result.getId());

        assertEquals(AgentRunStatus.COMPLETED, result.getStatus());
        assertEquals("最终答案", result.getFinalOutput());
        assertEquals(AgentTaskPlanStatus.COMPLETED, restored.getTaskPlan().getStatus());
        assertEquals(2, restored.getTaskPlan().getTasks().size());
        for (AgentTask task : restored.getTaskPlan().getTasks()) {
            assertEquals(AgentTaskStatus.COMPLETED, task.getStatus());
            assertNotNull(task.getChildRunId());
            assertNotNull(task.getResult());
        }
    }

    @Test
    public void shouldRouteRootResumeCommandToBlockedTask() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> planCall("发布应用",
            taskJson("deploy-task", "发布", "调用发布工具", null)));
        model.enqueue(prompt -> toolCalls(new ToolCall("deploy-call", "deploy", "{}")));
        model.enqueue(prompt -> new AiMessage("发布完成"));
        model.enqueue(prompt -> new AiMessage("任务完成"));
        model.enqueue(prompt -> new AiMessage("可以继续对话"));
        Agent agent = Agent.builder("approval-planning-agent")
            .chatModel(model)
            .tool(tool("deploy", args -> executions.incrementAndGet()))
            .toolApprovalPolicy((run, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .planningPolicy(AgentPlanningPolicy.enabled())
            .build();
        AgentRunner runner = runner(agent);

        AgentRun waitingRoot = runner.run(agent, "发布应用");
        AgentTaskProgress waiting = runner.getTaskProgress(waitingRoot.getId());
        AgentRun restoredWaitingRoot = runner.restore(waitingRoot.getId());

        assertEquals(AgentRunStatus.WAITING_FOR_CHILD, waitingRoot.getStatus());
        assertEquals(AgentRunStatus.WAITING_FOR_APPROVAL, waiting.getActiveRunStatus());
        assertEquals("deploy-task",
            restoredWaitingRoot.getTaskPlan().getActiveTask().getId());
        assertEquals(0, executions.get());

        AgentRun completed = runner.resume(waitingRoot.getId(),
            AgentResumeCommand.approveTool("deploy-call"));
        AgentRun next = runner.run(agent, completed.getConversationHistory(),
            new UserMessage("下一步"));

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals(AgentTaskPlanStatus.COMPLETED, completed.getTaskPlan().getStatus());
        assertEquals(1, executions.get());
        assertEquals("可以继续对话", next.getFinalOutput());
    }

    @Test
    public void shouldDelegateOnlyToAllowedAgent() {
        AgentScenarioTestSupport.QueueChatModel rootModel = new AgentScenarioTestSupport.QueueChatModel();
        AgentScenarioTestSupport.QueueChatModel specialistModel = new AgentScenarioTestSupport.QueueChatModel();
        rootModel.enqueue(prompt -> planCall("专项分析",
            taskJson("special", "专项任务", "完成专项分析", "specialist")));
        specialistModel.enqueue(prompt -> new AiMessage("专家结果"));
        rootModel.enqueue(prompt -> new AiMessage("汇总结果"));
        Agent root = Agent.builder("root")
            .chatModel(rootModel)
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true)
                .allowAgent("specialist").build())
            .build();
        Agent specialist = Agent.builder("specialist").chatModel(specialistModel).build();
        AgentRunner runner = new AgentRunner(new InMemoryAgentRunStore(),
            new InMemoryAgentLoader(root, specialist));

        AgentRun completed = runner.run(root, "执行专项分析");
        AgentTask task = completed.getTaskPlan().getTasks().get(0);

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals("specialist", runner.restore(task.getChildRunId()).getAgent().getId());
        assertEquals("专家结果", task.getResult());
    }

    @Test
    public void shouldRejectUnlistedAgent() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("非法委派",
            taskJson("task", "任务", "执行", "unknown-agent")));
        Agent agent = Agent.builder("limited-agent").chatModel(model)
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true).maxTasks(1).build())
            .build();

        AgentRun rejected = runner(agent).run(agent, "非法委派");

        assertEquals(AgentRunStatus.FAILED, rejected.getStatus());
        assertTrue(rejected.getError().getMessage().contains("not allowed"));
        assertNull(rejected.getTaskPlan());
    }

    @Test
    public void shouldRejectPlanThatExceedsTaskLimit() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("任务过多",
            taskJson("one", "任务一", "执行一", null) + ","
                + taskJson("two", "任务二", "执行二", null)));
        Agent agent = Agent.builder("task-limit-agent").chatModel(model)
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true).maxTasks(1).build())
            .build();

        AgentRun rejected = runner(agent).run(agent, "创建过多任务");

        assertEquals(AgentRunStatus.FAILED, rejected.getStatus());
        assertTrue(rejected.getError().getMessage().contains("maxTasks"));
    }

    @Test
    public void shouldContinueRemainingTasksAfterFailureWhenConfigured() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("容错计划",
            taskJson("first", "失败任务", "模拟失败", null) + ","
                + taskJson("second", "后续任务", "继续执行", null)));
        model.enqueue(prompt -> { throw new RuntimeException("child failed"); });
        model.enqueue(prompt -> new AiMessage("后续任务完成"));
        model.enqueue(prompt -> new AiMessage("已汇总失败和成功结果"));
        Agent agent = Agent.builder("continue-planning-agent").chatModel(model)
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true)
                .failureStrategy(AgentPlanningPolicy.FailureStrategy.CONTINUE).build())
            .build();

        AgentRun completed = runner(agent).run(agent, "执行容错计划");

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals(AgentTaskStatus.FAILED,
            completed.getTaskPlan().getTasks().get(0).getStatus());
        assertEquals(AgentTaskStatus.COMPLETED,
            completed.getTaskPlan().getTasks().get(1).getStatus());
    }

    @Test
    public void shouldPublishPlanningLifecycleEvents() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("记录事件",
            taskJson("one", "任务", "执行任务", null)));
        model.enqueue(prompt -> new AiMessage("子任务结果"));
        model.enqueue(prompt -> new AiMessage("汇总"));
        Agent agent = planningAgent("event-agent", model);
        List<AgentEvent> events = new ArrayList<>();
        AgentRunner runner = new AgentRunner(new InMemoryAgentRunStore(),
            new InMemoryAgentLoader(agent)).addEventListener(events::add);

        runner.run(agent, "执行并记录");

        assertTrue(hasEvent(events, AgentEventType.PLAN_CREATED));
        assertTrue(hasEvent(events, AgentEventType.TASK_STARTED));
        assertTrue(hasEvent(events, AgentEventType.TASK_COMPLETED));
    }

    @Test
    public void shouldSerializePlanAsPartOfRunSnapshot() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("序列化计划",
            taskJson("persist", "持久化", "验证快照", null)));
        model.enqueue(prompt -> new AiMessage("子任务结果"));
        model.enqueue(prompt -> new AiMessage("最终结果"));
        Agent agent = planningAgent("serialized-planning-agent", model);
        AgentRun completed = runner(agent).run(agent, "执行持久化计划");
        FastjsonAgentStoreSerializer serializer = new FastjsonAgentStoreSerializer();

        AgentRunSnapshot decoded = serializer.deserialize(
            serializer.serialize(completed.toSnapshot()), AgentRunSnapshot.class);

        assertNotNull(decoded.getTaskPlan());
        assertEquals(AgentTaskPlanStatus.COMPLETED, decoded.getTaskPlan().getStatus());
        assertEquals("persist", decoded.getTaskPlan().getTasks().get(0).getId());
    }

    @Test
    public void shouldAllowNextConversationTurnAfterPlannedRunCompletes() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("查询数据",
            taskJson("query", "查询", "查询目标数据", null)));
        model.enqueue(prompt -> new AiMessage("数据结果"));
        model.enqueue(prompt -> new AiMessage("查询汇总"));
        model.enqueue(prompt -> new AiMessage("继续对话"));
        Agent agent = planningAgent("conversation-planning-agent", model);
        AgentRunner runner = runner(agent);

        AgentRun first = runner.run(agent, "完成数据查询");
        AgentRun second = runner.run(agent, first.getConversationHistory(),
            new UserMessage("谢谢"));

        assertEquals(AgentRunStatus.COMPLETED, first.getStatus());
        assertEquals(AgentRunStatus.COMPLETED, second.getStatus());
        assertNull(second.getTaskPlan());
        assertEquals("继续对话", second.getFinalOutput());
    }

    @Test
    public void shouldContinuePlannedTaskThroughWorkerRetryAndLease() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger attempts = new AtomicInteger();
        model.enqueue(prompt -> planCall("恢复任务",
            taskJson("retry", "重试任务", "调用不稳定工具", null)));
        model.enqueue(prompt -> {
            assertFalse(hasTool(prompt.getTools(), AgentPlanningTool.NAME));
            return toolCalls(new ToolCall("unstable-call", "unstable", "{}"));
        });
        model.enqueue(prompt -> new AiMessage("子任务恢复"));
        model.enqueue(prompt -> new AiMessage("全部完成"));
        Agent agent = Agent.builder("worker-planning-agent").chatModel(model)
            .tool(tool("unstable", args -> {
                if (attempts.incrementAndGet() == 1) throw new RuntimeException("temporary");
                return "ok";
            }))
            .executionPolicy(AgentExecutionPolicy.builder()
                .retryPolicy(AgentRetryPolicy.builder().maxRetries(1)
                    .initialDelayMillis(0).maxDelayMillis(0).build())
                .build())
            .planningPolicy(AgentPlanningPolicy.enabled())
            .build();
        AgentRunner runner = runner(agent);
        AgentRun root = runner.start(agent, "使用 Worker 执行");

        try (AgentWorker worker = new AgentWorker("planning-worker", runner, 10000)) {
            for (int index = 0; index < 6; index++) worker.pollAndRun(1);
        }
        AgentRun completed = runner.restore(root.getId());

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals(AgentTaskPlanStatus.COMPLETED, completed.getTaskPlan().getStatus());
        assertEquals(2, attempts.get());
    }

    @Test
    public void shouldEnforceRootTokenBudgetAcrossChildRuns() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("预算任务",
            taskJson("consume", "消耗预算", "生成任务结果", null)));
        model.enqueue(prompt -> withTokens("子任务结果", 6));
        model.enqueue(prompt -> withTokens("最终汇总", 6));
        Agent agent = Agent.builder("budget-planning-agent").chatModel(model)
            .executionPolicy(AgentExecutionPolicy.builder()
                .budget(AgentBudget.builder().maxTotalTokens(10).build()).build())
            .planningPolicy(AgentPlanningPolicy.enabled()).build();

        AgentRun result = runner(agent).run(agent, "执行预算任务");

        assertEquals(AgentRunStatus.BUDGET_EXCEEDED, result.getStatus());
        assertEquals("maxTotalTokens", result.getBudgetExceededReason());
        assertEquals(12, result.getTotalTokens());
    }

    @Test
    public void shouldRoutePersistentApprovalCommandToCurrentChild() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("审批任务",
            taskJson("approve", "审批", "执行工具", null)));
        model.enqueue(prompt -> toolCalls(new ToolCall("approval-call", "protected", "{}")));
        Agent agent = Agent.builder("command-planning-agent").chatModel(model)
            .tool(tool("protected", args -> "ok"))
            .toolApprovalPolicy((run, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .planningPolicy(AgentPlanningPolicy.enabled()).build();
        AgentRunner runner = runner(agent);
        AgentRun root = runner.run(agent, "执行审批任务");

        com.agentsflex.agent.command.AgentRunCommand command = runner.submitCommand(
            root.getId(), AgentResumeCommand.approveTool("approval-call"));
        AgentTask current = root.getTaskPlan().getActiveTask();

        assertEquals(current.getChildRunId(), command.getRunId());
    }

    @Test
    public void shouldExposeConfiguredPlanningInstructionsAndDelegatesToModel() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> {
            Tool planning = findTool(prompt.getTools(), AgentPlanningTool.NAME);
            assertNotNull(planning);
            assertTrue(planning.getDescription().contains("先检索事实，再形成结论"));
            assertTrue(planning.getDescription().contains("researcher"));
            assertTrue(planning.getDescription().contains("检索公开资料"));
            Parameter tasks = findParameter(planning, "tasks");
            Parameter assigned = findChild(tasks.getItemsParameter(), "assignedAgentId");
            assertEquals(2, assigned.getEnums().length);
            assertEquals("configured-agent", assigned.getEnums()[0]);
            assertEquals("researcher", assigned.getEnums()[1]);
            return planCall("完成研究",
                taskJsonWithExpectedOutput("research", "资料检索", "查找资料",
                    "researcher", "返回来源与摘要"));
        });
        AgentScenarioTestSupport.QueueChatModel delegateModel =
            new AgentScenarioTestSupport.QueueChatModel();
        delegateModel.enqueue(prompt -> {
            String input = prompt.getMessages().get(prompt.getMessages().size() - 1)
                .getTextContent();
            assertTrue(input.contains("期望输出：返回来源与摘要"));
            return new AiMessage("研究结果");
        });
        model.enqueue(prompt -> new AiMessage("研究汇总"));
        Agent agent = Agent.builder("configured-agent").chatModel(model)
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true)
                .planningInstructions("先检索事实，再形成结论")
                .allowAgent("researcher")
                .build())
            .build();
        Agent researcher = Agent.builder("研究助手").id("researcher")
            .description("检索公开资料").attribute("domain", "research")
            .chatModel(delegateModel).build();
        AgentRunner runner = new AgentRunner(new InMemoryAgentRunStore(),
            new InMemoryAgentLoader(agent, researcher));

        AgentRun completed = runner.run(agent, "调研主题");

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals("返回来源与摘要",
            completed.getTaskPlan().getTasks().get(0).getExpectedOutput());
        assertEquals("research",
            researcher.getAttributes().get("domain"));
        assertTrue(agent.getPlanningPolicy().getAllowedAgentIds().contains("researcher"));
    }

    @Test
    public void shouldFailBeforeExecutionWhenAllowedAgentCannotBeLoaded() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        Agent agent = Agent.builder("missing-delegate-agent").chatModel(model)
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true)
                .allowAgent("missing-agent").build())
            .build();

        try {
            runner(agent).start(agent, "执行委派任务");
        } catch (IllegalStateException error) {
            assertTrue(error.getMessage().contains("missing-agent"));
            assertEquals(0, model.getCallCount());
            return;
        }
        throw new AssertionError("missing allowed Agent should be rejected before execution");
    }

    @Test
    public void shouldReplanPendingTasksAfterChildFailureAndPersistUpdateEvent() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("完成分析",
            taskJson("collect", "采集", "采集不可用数据", null) + ","
                + taskJson("analyze", "分析", "分析原始数据", null)));
        model.enqueue(prompt -> { throw new RuntimeException("source unavailable"); });
        model.enqueue(prompt -> {
            assertTrue(hasTool(prompt.getTools(), AgentPlanningTool.UPDATE_NAME));
            return updatePlanCall("数据源失败，改用缓存",
                taskJsonWithExpectedOutput("analyze", "分析缓存", "改用缓存数据",
                    null, "输出分析结论"));
        });
        model.enqueue(prompt -> new AiMessage("缓存分析完成"));
        model.enqueue(prompt -> new AiMessage("最终汇总"));
        List<AgentEvent> events = new ArrayList<>();
        Agent agent = Agent.builder("replanning-agent").chatModel(model)
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true)
                .failureStrategy(AgentPlanningPolicy.FailureStrategy.CONTINUE)
                .maxReplans(1).taskRevisionAllowed(true).build())
            .build();
        AgentRunner runner = new AgentRunner(new InMemoryAgentRunStore(),
            new InMemoryAgentLoader(agent)).addEventListener(events::add);

        AgentRun completed = runner.run(agent, "分析数据");
        AgentTask revised = completed.getTaskPlan().getTasks().get(1);

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals(1, completed.getTaskPlan().getRevisionCount());
        assertEquals("数据源失败，改用缓存",
            completed.getTaskPlan().getLastRevisionReason());
        assertEquals("分析缓存", revised.getTitle());
        assertEquals(AgentTaskStatus.COMPLETED, revised.getStatus());
        assertTrue(hasEvent(events, AgentEventType.PLAN_UPDATED));
    }

    @Test
    public void shouldLimitReplanningAttemptsAndContinueWithoutAnotherRevision() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("有限重规划",
            taskJson("first", "首次", "首次执行", null) + ","
                + taskJson("second", "第二次", "第二次执行", null)));
        model.enqueue(prompt -> { throw new RuntimeException("first failed"); });
        model.enqueue(prompt -> updatePlanCall("调整第二个任务",
            taskJson("second", "调整后任务", "再次执行", null)));
        model.enqueue(prompt -> { throw new RuntimeException("second failed"); });
        model.enqueue(prompt -> new AiMessage("已汇总两次失败"));
        Agent agent = Agent.builder("limited-replanning-agent").chatModel(model)
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true)
                .failureStrategy(AgentPlanningPolicy.FailureStrategy.CONTINUE)
                .maxReplans(1).taskRevisionAllowed(true).build())
            .build();

        AgentRun completed = runner(agent).run(agent, "执行计划");

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals(1, completed.getTaskPlan().getRevisionCount());
        assertEquals(5, model.getCallCount());
        assertEquals(AgentTaskStatus.FAILED,
            completed.getTaskPlan().getTasks().get(1).getStatus());
    }

    @Test
    public void shouldRejectAppendingTaskWhenPolicyOnlyAllowsRevision() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("禁止追加",
            taskJson("first", "失败", "触发失败", null) + ","
                + taskJson("existing", "已有", "已有任务", null)));
        model.enqueue(prompt -> { throw new RuntimeException("failed"); });
        model.enqueue(prompt -> updatePlanCall("尝试追加",
            taskJson("new-task", "新增", "新增任务", null)));
        Agent agent = Agent.builder("revision-only-agent").chatModel(model)
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true)
                .failureStrategy(AgentPlanningPolicy.FailureStrategy.CONTINUE)
                .maxReplans(1).taskRevisionAllowed(true).taskAppendAllowed(false).build())
            .build();

        AgentRun failed = runner(agent).run(agent, "执行计划");

        assertEquals(AgentRunStatus.FAILED, failed.getStatus());
        assertTrue(failed.getError().getMessage().contains("appending task is not allowed"));
    }

    @Test
    public void shouldAppendARecoveryTaskWhenPolicyAllowsIt() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("追加恢复任务",
            taskJson("first", "失败", "触发失败", null) + ","
                + taskJson("existing", "已有", "继续处理", null)));
        model.enqueue(prompt -> { throw new RuntimeException("failed"); });
        model.enqueue(prompt -> updatePlanCall("增加恢复步骤",
            taskJson("existing", "已有", "继续处理", null) + ","
                + taskJson("recovery", "恢复", "补偿失败", null)));
        model.enqueue(prompt -> new AiMessage("已有任务完成"));
        model.enqueue(prompt -> new AiMessage("恢复任务完成"));
        model.enqueue(prompt -> new AiMessage("汇总完成"));
        Agent agent = Agent.builder("append-planning-agent").chatModel(model)
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true)
                .failureStrategy(AgentPlanningPolicy.FailureStrategy.CONTINUE)
                .maxReplans(1).taskAppendAllowed(true).build())
            .build();

        AgentRun completed = runner(agent).run(agent, "执行计划");

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals(3, completed.getTaskPlan().getTasks().size());
        assertEquals("recovery", completed.getTaskPlan().getTasks().get(2).getId());
        assertEquals(AgentTaskStatus.COMPLETED,
            completed.getTaskPlan().getTasks().get(2).getStatus());
    }

    @Test
    public void shouldSkipRemainingTasksWhenModelDeclinesReplanning() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("可放弃计划",
            taskJson("first", "失败", "触发失败", null) + ","
                + taskJson("remaining", "剩余", "不再执行", null)));
        model.enqueue(prompt -> { throw new RuntimeException("failed"); });
        model.enqueue(prompt -> new AiMessage("无法形成可行的新计划"));
        Agent agent = Agent.builder("declined-replanning-agent").chatModel(model)
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true)
                .failureStrategy(AgentPlanningPolicy.FailureStrategy.CONTINUE)
                .maxReplans(1).taskRevisionAllowed(true).build())
            .build();

        AgentRun completed = runner(agent).run(agent, "执行计划");

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals("无法形成可行的新计划", completed.getFinalOutput());
        assertEquals(AgentTaskPlanStatus.COMPLETED, completed.getTaskPlan().getStatus());
        assertEquals(AgentTaskStatus.SKIPPED,
            completed.getTaskPlan().getTasks().get(1).getStatus());
    }

    @Test
    public void shouldTruncateParentTaskResultButKeepChildOutput() {
        final String original = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("大结果任务",
            taskJson("large", "生成结果", "返回大结果", null)));
        model.enqueue(prompt -> new AiMessage(original));
        model.enqueue(prompt -> {
            String messages = prompt.getMessages().toString();
            assertFalse(messages.contains(original));
            assertTrue(messages.contains("完整内容保留在子 Run 中"));
            return new AiMessage("汇总完成");
        });
        Agent agent = Agent.builder("truncation-agent").chatModel(model)
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true)
                .taskResultMaxLength(10).build())
            .build();
        AgentRunner runner = runner(agent);

        AgentRun completed = runner.run(agent, "生成大结果");
        AgentTask task = completed.getTaskPlan().getTasks().get(0);
        AgentRun child = runner.restore(task.getChildRunId());

        assertTrue(task.getResult().startsWith("0123456789"));
        assertTrue(task.getResult().contains("已截断"));
        assertEquals(original, child.getFinalOutput());
    }

    @Test
    public void shouldCompleteWithLastTaskResultWithoutSummaryModelCall() {
        AgentScenarioTestSupport.QueueChatModel model = new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> planCall("直接返回",
            taskJson("only", "唯一任务", "完成任务", null)));
        model.enqueue(prompt -> new AiMessage("子任务直接结果"));
        Agent agent = Agent.builder("no-summary-agent").chatModel(model)
            .planningPolicy(AgentPlanningPolicy.builder().enabled(true)
                .finalSummaryRequired(false).build())
            .build();

        AgentRun completed = runner(agent).run(agent, "执行唯一任务");

        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals("子任务直接结果", completed.getFinalOutput());
        assertEquals(2, model.getCallCount());
        assertTrue(completed.getConversationHistory().stream()
            .anyMatch(message -> "子任务直接结果".equals(message.getTextContent())));
    }

    private static Agent planningAgent(String name,
                                       AgentScenarioTestSupport.QueueChatModel model) {
        return Agent.builder(name).chatModel(model)
            .planningPolicy(AgentPlanningPolicy.enabled()).build();
    }

    private static AgentRunner runner(Agent agent) {
        return new AgentRunner(new InMemoryAgentRunStore(), new InMemoryAgentLoader(agent));
    }

    private static AiMessage planCall(String goal, String tasks) {
        return toolCalls(new ToolCall("plan-call", AgentPlanningTool.NAME,
            "{\"goal\":\"" + goal + "\",\"tasks\":[" + tasks + "]}"));
    }

    private static AiMessage updatePlanCall(String reason, String tasks) {
        return toolCalls(new ToolCall("update-plan-call", AgentPlanningTool.UPDATE_NAME,
            "{\"reason\":\"" + reason + "\",\"tasks\":[" + tasks + "]}"));
    }

    private static AiMessage withTokens(String content, int totalTokens) {
        AiMessage message = new AiMessage(content);
        message.setTotalTokens(totalTokens);
        return message;
    }

    private static String taskJson(String id, String title, String description,
                                   String assignedAgentId) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + title
            + "\",\"description\":\"" + description + "\""
            + (assignedAgentId == null ? "" : ",\"assignedAgentId\":\""
                + assignedAgentId + "\"") + "}";
    }

    private static String taskJsonWithExpectedOutput(String id, String title,
                                                     String description,
                                                     String assignedAgentId,
                                                     String expectedOutput) {
        String value = taskJson(id, title, description, assignedAgentId);
        return value.substring(0, value.length() - 1) + ",\"expectedOutput\":\""
            + expectedOutput + "\"}";
    }

    private static Tool findTool(List<Tool> tools, String name) {
        for (Tool tool : tools) if (name.equals(tool.getName())) return tool;
        return null;
    }

    private static Parameter findParameter(Tool tool, String name) {
        for (Parameter parameter : tool.getParameters()) {
            if (name.equals(parameter.getName())) return parameter;
        }
        return null;
    }

    private static Parameter findChild(Parameter parameter, String name) {
        if (parameter != null && parameter.getChildren() != null) {
            for (Parameter child : parameter.getChildren()) {
                if (name.equals(child.getName())) return child;
            }
        }
        return null;
    }

    private static boolean hasTool(List<Tool> tools, String name) {
        for (Tool tool : tools) if (name.equals(tool.getName())) return true;
        return false;
    }

    private static boolean hasEvent(List<AgentEvent> events, AgentEventType type) {
        for (AgentEvent event : events) if (event.getType() == type) return true;
        return false;
    }
}
