/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent;

import com.agentsflex.core.agent.registry.InMemoryAgentRegistry;
import com.agentsflex.core.agent.store.InMemoryAgentRunStore;
import com.agentsflex.core.agent.task.AgentPlanExecutor;
import com.agentsflex.core.agent.task.AgentPlanRun;
import com.agentsflex.core.agent.task.AgentPlanningContext;
import com.agentsflex.core.agent.task.AgentTask;
import com.agentsflex.core.agent.task.AgentTaskPlan;
import com.agentsflex.core.agent.task.AgentTaskPlanSnapshot;
import com.agentsflex.core.agent.task.AgentTaskPlanStatus;
import com.agentsflex.core.agent.task.AgentTaskProgress;
import com.agentsflex.core.agent.task.AgentTaskStatus;
import com.agentsflex.core.agent.task.InMemoryAgentTaskStore;
import com.agentsflex.core.agent.task.ModelAgentTaskPlanner;
import com.agentsflex.core.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agentsflex.core.agent.AgentScenarioTestSupport.tool;
import static com.agentsflex.core.agent.AgentScenarioTestSupport.toolCalls;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 验证任务规划、任务进度、子 Agent 和自动执行的完整协作过程。 */
public class AgentTaskPlanningScenarioTest {

    @Test
    public void shouldExecutePlannedTasksSequentiallyAndFinalizeRootRun() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("analysis result"));
        model.enqueue(prompt -> {
            assertTrue(prompt.getMessages().get(0).getTextContent().contains("总体目标"));
            return new AiMessage("test result");
        });
        model.enqueue(prompt -> {
            long childResults = prompt.getMessages().stream()
                .filter(message -> message.getTextContent().contains("Child Agent result:"))
                .count();
            assertEquals(2, childResults);
            return new AiMessage("final answer");
        });
        Agent agent = Agent.builder("planned-agent")
            .chatModel(model)
            .taskPlanner((definition, context) -> plan(context.getGoal(),
                task("analyze", 0), task("test", 1)))
            .build();
        AgentPlanExecutor executor = executor(new InMemoryAgentRegistry());

        AgentPlanRun result = executor.run(agent, "complete the change");

        assertEquals(AgentTaskPlanStatus.COMPLETED, result.getPlan().getStatus());
        assertEquals(AgentRunStatus.COMPLETED, result.getRootRun().getStatus());
        assertEquals("final answer", result.getRootRun().getFinalOutput());
        assertEquals(2, result.getPlan().getTasks().size());
        for (AgentTask plannedTask : result.getPlan().getTasks()) {
            assertEquals(AgentTaskStatus.COMPLETED, plannedTask.getStatus());
            assertNotNull(plannedTask.getChildRunId());
            assertNotNull(plannedTask.getResult());
        }
    }

    @Test
    public void shouldExposeCompletedCurrentAndPendingTasksAfterOneStep() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("first result"));
        Agent agent = Agent.builder("progress-agent")
            .chatModel(model)
            .taskPlanner((definition, context) -> plan(context.getGoal(),
                task("first", 0), task("second", 1), task("third", 2)))
            .build();
        AgentPlanExecutor executor = executor(new InMemoryAgentRegistry());
        AgentTaskPlanSnapshot started = executor.start(agent, "track progress");

        AgentPlanRun oneStep = executor.runNext(started.getPlanId());
        AgentTaskProgress progress = executor.getProgressByRootRunId(started.getRootRunId());

        assertEquals(AgentTaskPlanStatus.RUNNING, oneStep.getPlan().getStatus());
        assertEquals(3, progress.getTotalTaskCount());
        assertEquals(1, progress.getCompletedTaskCount());
        assertEquals(AgentTaskStatus.COMPLETED, progress.getTasks().get(0).getStatus());
        assertEquals(AgentTaskStatus.PENDING, progress.getTasks().get(1).getStatus());
        assertEquals(AgentTaskStatus.PENDING, progress.getTasks().get(2).getStatus());
    }

    @Test
    public void shouldResumeApprovalInsideCurrentTaskAndContinuePlan() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger executions = new AtomicInteger();
        model.enqueue(prompt -> toolCalls(new ToolCall("deploy-1", "deploy", "{}")));
        model.enqueue(prompt -> new AiMessage("deployed"));
        model.enqueue(prompt -> new AiMessage("deployment complete"));
        Agent agent = Agent.builder("approval-plan-agent")
            .chatModel(model)
            .tool(tool("deploy", args -> executions.incrementAndGet()))
            .toolApprovalPolicy((run, call, value) -> ToolApprovalDecision.REQUIRE_APPROVAL)
            .taskPlanner((definition, context) -> plan(context.getGoal(), task("deploy", 0)))
            .build();
        AgentPlanExecutor executor = executor(new InMemoryAgentRegistry());

        AgentPlanRun waiting = executor.run(agent, "deploy application");
        AgentTaskProgress waitingProgress = executor.getProgress(waiting.getPlan().getPlanId());

        assertEquals(AgentTaskPlanStatus.WAITING, waiting.getPlan().getStatus());
        assertEquals(AgentTaskStatus.WAITING, waitingProgress.getCurrentTask().getStatus());
        assertEquals(AgentRunStatus.WAITING_FOR_APPROVAL,
            waitingProgress.getActiveRunStatus());
        assertEquals(0, executions.get());

        AgentPlanRun completed = executor.resume(waiting.getPlan().getPlanId(),
            AgentResumeCommand.approveTool("deploy-1"));

        assertEquals(AgentTaskPlanStatus.COMPLETED, completed.getPlan().getStatus());
        assertEquals("deployment complete", completed.getRootRun().getFinalOutput());
        assertEquals(1, executions.get());
    }

    @Test
    public void shouldDelegateAssignedTaskToRegisteredChildAgent() {
        AgentScenarioTestSupport.QueueChatModel rootModel =
            new AgentScenarioTestSupport.QueueChatModel();
        AgentScenarioTestSupport.QueueChatModel specialistModel =
            new AgentScenarioTestSupport.QueueChatModel();
        specialistModel.enqueue(prompt -> new AiMessage("specialist result"));
        rootModel.enqueue(prompt -> new AiMessage("combined result"));
        Agent rootAgent = Agent.builder("root-planning-agent")
            .chatModel(rootModel)
            .taskPlanner((definition, context) -> plan(context.getGoal(),
                AgentTask.builder("specialized task")
                    .assignedAgentId("specialist-agent")
                    .position(0)
                    .build()))
            .build();
        Agent specialist = Agent.builder("specialist-agent")
            .chatModel(specialistModel)
            .build();
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        registry.register(specialist);
        AgentPlanExecutor executor = executor(registry);

        AgentPlanRun result = executor.run(rootAgent, "delegate work");
        AgentTask completedTask = result.getPlan().getTasks().get(0);
        AgentRun delegatedRun = executor.getAgentRunner().restore(completedTask.getChildRunId());

        assertEquals(AgentTaskPlanStatus.COMPLETED, result.getPlan().getStatus());
        assertEquals("specialist-agent", delegatedRun.getAgent().getId());
        assertEquals("specialist result", completedTask.getResult());
    }

    @Test
    public void shouldContinuePlanAfterWorkerCompletesScheduledRetry() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        AtomicInteger attempts = new AtomicInteger();
        model.enqueue(prompt -> toolCalls(new ToolCall("retry-task", "unstable", "{}")));
        model.enqueue(prompt -> new AiMessage("task recovered"));
        model.enqueue(prompt -> new AiMessage("all recovered"));
        Agent agent = Agent.builder("worker-plan-agent")
            .chatModel(model)
            .tool(tool("unstable", args -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new RuntimeException("temporary");
                }
                return "ok";
            }))
            .executionPolicy(AgentExecutionPolicy.builder()
                .retryPolicy(AgentRetryPolicy.builder()
                    .maxRetries(1)
                    .initialDelayMillis(0)
                    .maxDelayMillis(0)
                    .build())
                .build())
            .taskPlanner((definition, context) -> plan(context.getGoal(), task("retry", 0)))
            .build();
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        InMemoryAgentRegistry registry = new InMemoryAgentRegistry();
        AgentRunner runner = new AgentRunner(runStore, registry);
        AgentPlanExecutor executor = new AgentPlanExecutor(runner, new InMemoryAgentTaskStore());

        AgentPlanRun waiting = executor.run(agent, "recover task");
        assertEquals(AgentTaskPlanStatus.WAITING, waiting.getPlan().getStatus());
        assertEquals(AgentRunStatus.RETRY_SCHEDULED, waiting.getActiveRun().getStatus());

        List<AgentRun> workerResults =
            new AgentWorker("plan-worker", runner, 10000).pollAndRun(1);
        AgentPlanRun completed = executor.runUntilBlocked(waiting.getPlan().getPlanId());

        assertEquals(1, workerResults.size());
        assertEquals(AgentTaskPlanStatus.COMPLETED, completed.getPlan().getStatus());
        assertEquals("all recovered", completed.getRootRun().getFinalOutput());
        assertEquals(2, attempts.get());
    }

    @Test
    public void shouldCreateStructuredPlanWithModelPlanner() {
        AgentScenarioTestSupport.QueueChatModel model =
            new AgentScenarioTestSupport.QueueChatModel();
        model.enqueue(prompt -> new AiMessage("```json\n{\"tasks\":["
            + "{\"id\":\"inspect\",\"title\":\"inspect\","
            + "\"description\":\"inspect code\"},"
            + "{\"id\":\"test\",\"parentTaskId\":\"inspect\","
            + "\"title\":\"test\",\"assignedAgentId\":\"test-agent\"}]}\n```"));
        Agent agent = Agent.builder("model-planner-agent").chatModel(model).build();

        AgentTaskPlan plan = new ModelAgentTaskPlanner(5).createPlan(agent,
            new AgentPlanningContext("fix issue", "root-1", null));

        assertEquals("fix issue", plan.getGoal());
        assertEquals(2, plan.getTasks().size());
        assertEquals("inspect code", plan.getTasks().get(0).getDescription());
        assertEquals("inspect", plan.getTasks().get(1).getParentTaskId());
        assertEquals("test-agent", plan.getTasks().get(1).getAssignedAgentId());
    }

    private AgentPlanExecutor executor(InMemoryAgentRegistry registry) {
        return new AgentPlanExecutor(
            new AgentRunner(new InMemoryAgentRunStore(), registry),
            new InMemoryAgentTaskStore());
    }

    private static AgentTaskPlan plan(String goal, AgentTask... tasks) {
        return new AgentTaskPlan(goal, Arrays.asList(tasks));
    }

    private static AgentTask task(String title, int position) {
        return AgentTask.builder(title)
            .description("complete " + title)
            .position(position)
            .build();
    }
}
