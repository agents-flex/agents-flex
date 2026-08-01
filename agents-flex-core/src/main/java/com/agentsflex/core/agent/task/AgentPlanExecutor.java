/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.task;

import com.agentsflex.core.agent.Agent;
import com.agentsflex.core.agent.AgentResumeCommand;
import com.agentsflex.core.agent.AgentRun;
import com.agentsflex.core.agent.AgentRunSnapshot;
import com.agentsflex.core.agent.AgentRunStatus;
import com.agentsflex.core.agent.AgentRunner;
import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 按顺序执行持久化任务计划的协调器。
 *
 * <p>根 AgentRun 保存总体目标和所有子任务结果。每个任务使用独立子 AgentRun 执行，任务未指定
 * assignedAgentId 时复用根 Agent，指定后通过 AgentRegistry 解析对应子 Agent。</p>
 */
public final class AgentPlanExecutor {

    private final AgentRunner agentRunner;
    private final AgentTaskStore taskStore;

    public AgentPlanExecutor(AgentRunner agentRunner, AgentTaskStore taskStore) {
        if (agentRunner == null || taskStore == null) {
            throw new IllegalArgumentException("agentRunner and taskStore must not be null");
        }
        this.agentRunner = agentRunner;
        this.taskStore = taskStore;
    }

    public AgentRunner getAgentRunner() { return agentRunner; }
    public AgentTaskStore getTaskStore() { return taskStore; }

    /** 创建根 Run、调用规划器并保存尚未执行的任务计划。 */
    public AgentTaskPlanSnapshot start(Agent agent, String goal) {
        if (agent == null || agent.getTaskPlanner() == null) {
            throw new IllegalStateException("AgentTaskPlanner is not configured");
        }
        AgentRun rootRun = agentRunner.start(agent, goal);
        AgentPlanningContext context = new AgentPlanningContext(
            goal, rootRun.getId(), rootRun.getMetadata());
        AgentTaskPlan plan = agent.getTaskPlanner().createPlan(agent, context);
        List<AgentTask> tasks = normalizeTasks(plan.getTasks());
        AgentTaskPlanSnapshot snapshot = AgentTaskPlanSnapshot.builder(
                UUID.randomUUID().toString(), rootRun.getId(), plan.getGoal())
            .tasks(tasks)
            .build();
        return taskStore.save(snapshot, -1);
    }

    /** 创建计划并自动执行到完成或阻塞。 */
    public AgentPlanRun run(Agent agent, String goal) {
        return runUntilBlocked(start(agent, goal).getPlanId());
    }

    /** 最多推进一个任务；任务内部仍会执行到终止或阻塞状态。 */
    public AgentPlanRun runNext(String planId) {
        return advance(requirePlan(planId));
    }

    /** 持续推进任务和最终汇总，直到计划完成或等待外部事件。 */
    public AgentPlanRun runUntilBlocked(String planId) {
        AgentTaskPlanSnapshot plan = requirePlan(planId);
        while (!plan.getStatus().isTerminal()) {
            AgentPlanRun result = advance(plan);
            plan = result.getPlan();
            if (plan.getStatus().isBlocked()) {
                return result;
            }
        }
        return result(plan, null);
    }

    /** 恢复当前活动子 Run 或根 Run，并继续执行整个计划。 */
    public AgentPlanRun resume(String planId, AgentResumeCommand command) {
        AgentTaskPlanSnapshot plan = requirePlan(planId);
        if (plan.getStatus() != AgentTaskPlanStatus.WAITING
            || !StringUtil.hasText(plan.getActiveRunId())) {
            throw new IllegalStateException("task plan is not waiting for a resumable run");
        }
        AgentRun resumed = agentRunner.resume(plan.getActiveRunId(), command);
        plan = processActiveRun(plan, resumed);
        if (plan.getStatus().isTerminal() || plan.getStatus().isBlocked()) {
            return result(plan, resumed);
        }
        return runUntilBlocked(planId);
    }

    /** 返回任务列表、完成数量和当前活动 Run 的等待信息。 */
    public AgentTaskProgress getProgress(String planId) {
        AgentTaskPlanSnapshot plan = requirePlan(planId);
        AgentRun active = restoreIfPresent(plan.getActiveRunId());
        return new AgentTaskProgress(plan,
            active == null ? null : active.getStatus(),
            active == null ? null : active.getSuspension());
    }

    public AgentTaskProgress getProgressByRootRunId(String rootRunId) {
        AgentTaskPlanSnapshot plan = taskStore.loadByRootRunId(rootRunId);
        if (plan == null) {
            throw new IllegalStateException("task plan not found for root run: " + rootRunId);
        }
        return getProgress(plan.getPlanId());
    }

    private AgentPlanRun advance(AgentTaskPlanSnapshot plan) {
        if (plan.getStatus().isTerminal()) { return result(plan, null); }

        if (StringUtil.hasText(plan.getActiveRunId())) {
            AgentRun active = agentRunner.restore(plan.getActiveRunId());
            if (!active.getStatus().isTerminal() && !active.getStatus().isBlocked()) {
                active = agentRunner.runUntilBlocked(active);
            }
            AgentTaskPlanSnapshot processed = processActiveRun(plan, active);
            return result(processed, active);
        }

        AgentTask next = nextTask(plan.getTasks());
        if (next == null) {
            return finalizeRoot(plan);
        }
        return executeTask(plan, next);
    }

    private AgentPlanRun executeTask(AgentTaskPlanSnapshot plan, AgentTask task) {
        AgentRun root = agentRunner.restore(plan.getRootRunId());
        if (root.getStatus().isTerminal()) {
            AgentTaskPlanSnapshot failed = save(plan.toBuilder()
                .status(AgentTaskPlanStatus.FAILED)
                .error("root AgentRun is already terminal: " + root.getStatus())
                .completedAt(System.currentTimeMillis())
                .build(), plan.getVersion());
            return result(failed, root);
        }
        String agentId = StringUtil.hasText(task.getAssignedAgentId())
            ? task.getAssignedAgentId() : root.getAgent().getId();
        AgentRun child = agentRunner.startChild(root, agentId, taskInput(plan, task));
        child.putMetadata("agentTaskPlanId", plan.getPlanId());
        child.putMetadata("agentTaskId", task.getId());
        agentRunner.checkpoint(child);

        long now = System.currentTimeMillis();
        AgentTask runningTask = task.toBuilder()
            .status(AgentTaskStatus.RUNNING)
            .childRunId(child.getId())
            .startedAt(now)
            .build();
        AgentTaskPlanSnapshot running = save(plan.toBuilder()
            .status(AgentTaskPlanStatus.RUNNING)
            .tasks(replaceTask(plan.getTasks(), runningTask))
            .activeTaskId(task.getId())
            .activeRunId(child.getId())
            .build(), plan.getVersion());

        child = agentRunner.runUntilBlocked(child);
        AgentTaskPlanSnapshot processed = processActiveRun(running, child);
        return result(processed, child);
    }

    private AgentTaskPlanSnapshot processActiveRun(AgentTaskPlanSnapshot plan, AgentRun active) {
        if (active.getStatus().isBlocked()) {
            List<AgentTask> tasks = plan.getTasks();
            AgentTask current = plan.getCurrentTask();
            if (current != null && current.getStatus() != AgentTaskStatus.WAITING) {
                current = current.toBuilder().status(AgentTaskStatus.WAITING).build();
                tasks = replaceTask(tasks, current);
            }
            return save(plan.toBuilder()
                .status(AgentTaskPlanStatus.WAITING)
                .tasks(tasks)
                .build(), plan.getVersion());
        }
        if (!active.getStatus().isTerminal()) {
            return plan;
        }
        if (active.getId().equals(plan.getRootRunId())) {
            return finishFromRoot(plan, active);
        }
        return finishTask(plan, active);
    }

    private AgentTaskPlanSnapshot finishTask(AgentTaskPlanSnapshot plan, AgentRun child) {
        AgentTask current = plan.getCurrentTask();
        if (current == null || !child.getId().equals(current.getChildRunId())) {
            throw new IllegalStateException("active child run does not match current task");
        }
        agentRunner.resumeParentFromChild(child);
        long now = System.currentTimeMillis();
        if (child.getStatus() == AgentRunStatus.COMPLETED) {
            AgentTask completed = current.toBuilder()
                .status(AgentTaskStatus.COMPLETED)
                .result(child.getFinalOutput())
                .completedAt(now)
                .build();
            return save(plan.toBuilder()
                .status(AgentTaskPlanStatus.RUNNING)
                .tasks(replaceTask(plan.getTasks(), completed))
                .activeTaskId(null)
                .activeRunId(null)
                .build(), plan.getVersion());
        }

        AgentTaskStatus taskStatus = child.getStatus() == AgentRunStatus.CANCELLED
            ? AgentTaskStatus.CANCELLED : AgentTaskStatus.FAILED;
        AgentTask failed = current.toBuilder()
            .status(taskStatus)
            .error(runError(child))
            .completedAt(now)
            .build();
        AgentTaskPlanStatus planStatus = child.getStatus() == AgentRunStatus.CANCELLED
            ? AgentTaskPlanStatus.CANCELLED : AgentTaskPlanStatus.FAILED;
        return save(plan.toBuilder()
            .status(planStatus)
            .tasks(replaceTask(plan.getTasks(), failed))
            .activeTaskId(null)
            .activeRunId(null)
            .error(runError(child))
            .completedAt(now)
            .build(), plan.getVersion());
    }

    private AgentPlanRun finalizeRoot(AgentTaskPlanSnapshot plan) {
        AgentRun root = agentRunner.restore(plan.getRootRunId());
        AgentTaskPlanSnapshot finalizing = save(plan.toBuilder()
            .status(AgentTaskPlanStatus.FINALIZING)
            .activeTaskId(null)
            .activeRunId(root.getId())
            .build(), plan.getVersion());
        if (!root.getStatus().isTerminal() && !root.getStatus().isBlocked()) {
            root = agentRunner.runUntilBlocked(root);
        }
        AgentTaskPlanSnapshot finished = processActiveRun(finalizing, root);
        return result(finished, root);
    }

    private AgentTaskPlanSnapshot finishFromRoot(AgentTaskPlanSnapshot plan, AgentRun root) {
        long now = System.currentTimeMillis();
        if (root.getStatus() == AgentRunStatus.COMPLETED) {
            return save(plan.toBuilder()
                .status(AgentTaskPlanStatus.COMPLETED)
                .activeRunId(null)
                .completedAt(now)
                .build(), plan.getVersion());
        }
        AgentTaskPlanStatus status = root.getStatus() == AgentRunStatus.CANCELLED
            ? AgentTaskPlanStatus.CANCELLED : AgentTaskPlanStatus.FAILED;
        return save(plan.toBuilder()
            .status(status)
            .activeRunId(null)
            .error(runError(root))
            .completedAt(now)
            .build(), plan.getVersion());
    }

    private AgentPlanRun result(AgentTaskPlanSnapshot plan, AgentRun active) {
        AgentRun root = agentRunner.restore(plan.getRootRunId());
        AgentRun resolvedActive = active;
        if (resolvedActive == null && StringUtil.hasText(plan.getActiveRunId())) {
            resolvedActive = agentRunner.restore(plan.getActiveRunId());
        }
        return new AgentPlanRun(plan, root, resolvedActive);
    }

    private AgentTaskPlanSnapshot requirePlan(String planId) {
        AgentTaskPlanSnapshot plan = taskStore.load(planId);
        if (plan == null) {
            throw new IllegalStateException("AgentTaskPlan not found: " + planId);
        }
        return plan;
    }

    private AgentTaskPlanSnapshot save(AgentTaskPlanSnapshot plan, long expectedVersion) {
        return taskStore.save(plan, expectedVersion);
    }

    private AgentRun restoreIfPresent(String runId) {
        return StringUtil.hasText(runId) ? agentRunner.restore(runId) : null;
    }

    private AgentTask nextTask(List<AgentTask> tasks) {
        for (AgentTask task : tasks) {
            if (task.getStatus() == AgentTaskStatus.PENDING
                || task.getStatus() == AgentTaskStatus.READY) {
                return task;
            }
        }
        return null;
    }

    private List<AgentTask> normalizeTasks(List<AgentTask> tasks) {
        List<AgentTask> normalized = new ArrayList<>(tasks.size());
        Set<String> taskIds = new HashSet<>();
        for (int i = 0; i < tasks.size(); i++) {
            AgentTask task = tasks.get(i);
            if (!taskIds.add(task.getId())) {
                throw new IllegalStateException("duplicate task id: " + task.getId());
            }
            normalized.add(task.toBuilder()
                .position(i)
                .status(AgentTaskStatus.PENDING)
                .childRunId(null)
                .result(null)
                .error(null)
                .startedAt(0)
                .completedAt(0)
                .build());
        }
        for (AgentTask task : normalized) {
            if (StringUtil.hasText(task.getParentTaskId())) {
                if (task.getId().equals(task.getParentTaskId())) {
                    throw new IllegalStateException("task cannot be its own parent: " + task.getId());
                }
                if (!taskIds.contains(task.getParentTaskId())) {
                    throw new IllegalStateException(
                        "parent task not found: " + task.getParentTaskId());
                }
            }
        }
        normalized.sort(Comparator.comparingInt(AgentTask::getPosition));
        return normalized;
    }

    private List<AgentTask> replaceTask(List<AgentTask> tasks, AgentTask replacement) {
        List<AgentTask> result = new ArrayList<>(tasks.size());
        boolean replaced = false;
        for (AgentTask task : tasks) {
            if (task.getId().equals(replacement.getId())) {
                result.add(replacement);
                replaced = true;
            } else {
                result.add(task);
            }
        }
        if (!replaced) {
            throw new IllegalStateException("task not found in plan: " + replacement.getId());
        }
        return result;
    }

    private String taskInput(AgentTaskPlanSnapshot plan, AgentTask task) {
        StringBuilder input = new StringBuilder();
        input.append("总体目标：").append(plan.getGoal())
            .append("\n当前任务：").append(task.getTitle());
        if (StringUtil.hasText(task.getDescription())) {
            input.append("\n执行要求：").append(task.getDescription());
        }
        input.append("\n请只完成当前任务，并返回可供父 Agent 汇总的结果。");
        return input.toString();
    }

    private String runError(AgentRun run) {
        if (run.getError() != null && StringUtil.hasText(run.getError().getMessage())) {
            return run.getError().getMessage();
        }
        if (StringUtil.hasText(run.getBudgetExceededReason())) {
            return run.getBudgetExceededReason();
        }
        return "AgentRun ended with status " + run.getStatus();
    }
}
