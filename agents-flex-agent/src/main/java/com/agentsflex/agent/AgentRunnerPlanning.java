/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.agent.loader.AgentLoader;
import com.agentsflex.agent.task.AgentPlanningPolicy;
import com.agentsflex.agent.task.AgentPlanningTool;
import com.agentsflex.agent.task.AgentTask;
import com.agentsflex.agent.task.AgentTaskPlan;
import com.agentsflex.agent.task.AgentTaskPlanStatus;
import com.agentsflex.agent.task.AgentTaskProgress;
import com.agentsflex.agent.task.AgentTaskStatus;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.message.ToolMessage;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 封装 {@link AgentRunner} 内部的任务规划状态转换。
 *
 * <p>该类是包内实现细节，不是新的公开扩展点。它负责解析内置规划工具、推进计划、关联父子 Run、
 * 回写子任务结果以及准备规划工具；Snapshot、租约和普通运行循环仍由 AgentRunner 统一管理。</p>
 */
final class AgentRunnerPlanning {

    private final AgentRunner runner;
    private final AgentLoader agentLoader;
    private final AgentEventPublisher eventPublisher;

    AgentRunnerPlanning(AgentRunner runner, AgentLoader agentLoader,
                        AgentEventPublisher eventPublisher) {
        this.runner = runner;
        this.agentLoader = agentLoader;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 判断 ToolCall 是否属于框架内置的计划创建或调整工具。
     */
    boolean isPlanningTool(ToolCall call) {
        return call != null && (AgentPlanningTool.NAME.equals(call.getName())
            || AgentPlanningTool.UPDATE_NAME.equals(call.getName()));
    }

    /**
     * 应用规划 ToolCall，只修改 Run 中的计划状态并返回对应 ToolMessage。
     * 调用方应先保存 ToolMessage 和 Snapshot，再调用 {@link #notifyPlanChanged(AgentRun, ToolCall)}。
     */
    ToolMessage applyToolCall(AgentRun run, ToolCall call) {
        return AgentPlanningTool.NAME.equals(call.getName())
            ? createTaskPlan(run, call) : updateTaskPlan(run, call);
    }

    /**
     * 在规划 ToolCall 的状态已经保存后发布相应生命周期事件。
     */
    void notifyPlanChanged(AgentRun run, ToolCall call) {
        if (AgentPlanningTool.NAME.equals(call.getName())) {
            eventPublisher.notifyPlanCreated(run, run.getTaskPlan());
        } else {
            eventPublisher.notifyPlanUpdated(run, run.getTaskPlan());
        }
    }

    /**
     * 优先推进 Run 中已经存在的任务计划。
     *
     * @return 规划产生独立动作时返回步骤结果；无需拦截普通模型步骤时返回 {@code null}
     */
    AgentStepResult advance(AgentRun run) {
        AgentTaskPlan plan = run.getTaskPlan();
        if (plan == null || plan.getStatus() == AgentTaskPlanStatus.COMPLETED
            || plan.getStatus() == AgentTaskPlanStatus.FINALIZING
            || plan.getStatus() == AgentTaskPlanStatus.REPLANNING) return null;
        if (plan.getActiveTask() != null) {
            return AgentStepResult.of(null, null, null);
        }
        AgentTask next = plan.getNextTask();
        if (next == null) {
            run.updateTaskPlan(plan.beginFinalizing(System.currentTimeMillis()));
            runner.saveSnapshot(run);
            if (!run.getAgent().getPlanningPolicy().isFinalSummaryRequired()) {
                AiMessage finalMessage = new AiMessage(lastTaskResult(plan));
                run.getPrompt().addMessage(finalMessage);
                return runner.complete(run, null, finalMessage);
            }
            return null;
        }
        String agentId = StringUtil.hasText(next.getAssignedAgentId())
            ? next.getAssignedAgentId() : run.getAgent().getId();
        runner.startChild(run, agentId, taskInput(plan, next));
        return AgentStepResult.of(null, null, null);
    }

    /**
     * 在父子 Snapshot 保存前，把新建子 Run 与当前计划任务关联。
     */
    void bindChild(AgentRun parent, AgentRun child, String childAgentId) {
        AgentTaskPlan plan = parent.getTaskPlan();
        if (plan == null || plan.getActiveTask() != null || plan.getNextTask() == null) return;
        AgentTask task = plan.getNextTask();
        validateTaskAgent(parent, task, childAgentId);
        parent.updateTaskPlan(plan.startTask(task.getId(), child.getId(),
            System.currentTimeMillis()));
        child.putMetadata("agentTaskPlanId", plan.getId());
        child.putMetadata("agentTaskId", task.getId());
    }

    /**
     * 在父子 Snapshot 已经原子保存后发布计划任务开始事件。
     */
    void notifyTaskStarted(AgentRun parent, AgentRun child) {
        AgentTaskPlan plan = parent.getTaskPlan();
        if (plan != null && plan.getActiveTask() != null) {
            eventPublisher.notifyTaskStarted(parent, plan.getActiveTask(), child);
        }
    }

    /**
     * 将终止子 Run 的结果写回计划并恢复父 Run。
     * 重复处理已恢复的父 Run 时不会再次追加结果。
     */
    AgentRun resumeParentFromChild(AgentRun child) {
        if (child == null || !child.getStatus().isTerminal()
            || !StringUtil.hasText(child.getParentRunId())) {
            return null;
        }
        AgentRun parent = runner.restore(child.getParentRunId());
        AgentSuspension suspension = parent.getSuspension();
        if (parent.getStatus() != AgentRunStatus.WAITING_FOR_CHILD || suspension == null
            || !child.getId().equals(suspension.getCorrelationId())) {
            return parent;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("childRunId", child.getId());
        result.put("agentId", child.getAgent().getId());
        result.put("status", child.getStatus().name());
        String taskResult = limitTaskResult(parent, child.getFinalOutput());
        result.put("output", taskResult);
        if (!Objects.equals(taskResult, child.getFinalOutput())) {
            result.put("outputTruncated", true);
        }
        result.put("error", child.getError() == null ? null : child.getError().getMessage());

        finishActiveTask(parent, child, taskResult);
        parent.getPrompt().addUserMessage("Child Agent result: " + JSON.toJSONString(result));
        return runner.submitResume(parent, AgentResumeCommand.childCompleted(child.getId()));
    }

    /**
     * 查询计划以及当前计划子 Run 的真实运行状态。
     */
    AgentTaskProgress getTaskProgress(String runId) {
        AgentRun root = runner.restore(runId);
        AgentTaskPlan plan = root.getTaskPlan();
        if (plan == null) return null;
        AgentRun child = currentChild(root);
        return new AgentTaskProgress(plan,
            child == null ? root.getStatus() : child.getStatus(),
            child == null ? root.getSuspension() : child.getSuspension());
    }

    /**
     * 返回父 Run 当前计划关联的子 Run；没有活动计划任务时返回 {@code null}。
     */
    AgentRun currentChild(AgentRun parent) {
        if (parent == null || parent.getStatus() != AgentRunStatus.WAITING_FOR_CHILD) return null;
        AgentTaskPlan plan = parent.getTaskPlan();
        AgentTask task = plan == null ? null : plan.getActiveTask();
        return task == null || !StringUtil.hasText(task.getChildRunId())
            ? null : runner.restore(task.getChildRunId());
    }

    /**
     * 加载白名单中的完整 Agent，并向当前 Run 的 Prompt 装配规划工具。
     */
    void prepareTools(AgentRun run) {
        if (run.isPlanningToolsPrepared()) return;
        List<Agent> delegates = new ArrayList<>();
        for (String agentId : run.getAgent().getPlanningPolicy().getAllowedAgentIds()) {
            Agent delegate = agentLoader.loadActive(agentId);
            if (delegate == null) {
                throw new IllegalStateException(
                    "Allowed planning Agent cannot be loaded: " + agentId);
            }
            delegates.add(delegate);
        }
        run.preparePlanningTools(delegates);
    }

    /**
     * 在 Run 完成时收束重规划或最终汇总状态。
     */
    void finishPlan(AgentRun run) {
        AgentTaskPlan plan = run.getTaskPlan();
        if (plan != null && plan.getStatus() == AgentTaskPlanStatus.REPLANNING) {
            plan = plan.stop("模型未提交计划调整，剩余任务已跳过", System.currentTimeMillis());
        }
        if (plan != null && plan.getStatus() == AgentTaskPlanStatus.FINALIZING) {
            run.updateTaskPlan(plan.complete(System.currentTimeMillis()));
        }
    }

    private ToolMessage createTaskPlan(AgentRun run, ToolCall call) {
        if (!run.isPlanningEnabled()) {
            throw new IllegalArgumentException("task planning is not enabled for this run");
        }
        if (run.getTaskPlan() != null) {
            throw new IllegalArgumentException("an AgentRun can only create one task plan");
        }
        JSONObject object = arguments(call);
        String goal = object.getString("goal");
        JSONArray values = object.getJSONArray("tasks");
        AgentPlanningPolicy policy = run.getAgent().getPlanningPolicy();
        if (!StringUtil.hasText(goal) || values == null || values.isEmpty()) {
            throw new IllegalArgumentException("planning goal and tasks are required");
        }
        if (values.size() > policy.getMaxTasks()) {
            throw new IllegalArgumentException("task count exceeds maxTasks: "
                + policy.getMaxTasks());
        }
        List<AgentTask> tasks = parseTasks(run, values);
        AgentTaskPlan plan = AgentTaskPlan.create(goal, tasks);
        run.updateTaskPlan(plan);
        return toolResult(call, "planId", plan.getId(), "taskCount", tasks.size());
    }

    private ToolMessage updateTaskPlan(AgentRun run, ToolCall call) {
        AgentTaskPlan plan = run.getTaskPlan();
        AgentPlanningPolicy policy = run.getAgent().getPlanningPolicy();
        if (plan == null || plan.getStatus() != AgentTaskPlanStatus.REPLANNING) {
            throw new IllegalArgumentException("task plan is not waiting for an update");
        }
        if (plan.getRevisionCount() >= policy.getMaxReplans()) {
            throw new IllegalArgumentException("task plan has reached maxReplans");
        }
        JSONObject object = arguments(call);
        String reason = object.getString("reason");
        JSONArray values = object.getJSONArray("tasks");
        if (!StringUtil.hasText(reason) || values == null || values.isEmpty()) {
            throw new IllegalArgumentException("revision reason and tasks are required");
        }
        AgentTaskPlan updated = plan.revisePending(parseTasks(run, values), reason,
            policy.isTaskRevisionAllowed(), policy.isTaskAppendAllowed(),
            policy.getMaxTasks(), System.currentTimeMillis());
        run.updateTaskPlan(updated);
        return toolResult(call, "planId", updated.getId(),
            "revisionCount", updated.getRevisionCount(), "pendingTaskCount", values.size());
    }

    private void finishActiveTask(AgentRun parent, AgentRun child, String taskResult) {
        AgentTaskPlan plan = parent.getTaskPlan();
        if (plan == null || plan.getActiveTask() == null
            || !child.getId().equals(plan.getActiveTask().getChildRunId())) return;
        AgentTask activeTask = plan.getActiveTask();
        AgentTaskStatus taskStatus = child.getStatus() == AgentRunStatus.COMPLETED
            ? AgentTaskStatus.COMPLETED : (child.getStatus() == AgentRunStatus.CANCELLED
            ? AgentTaskStatus.CANCELLED : AgentTaskStatus.FAILED);
        String taskError = child.getError() == null
            ? (child.getStatus() == AgentRunStatus.COMPLETED ? null : child.getStatus().name())
            : child.getError().getMessage();
        AgentTaskPlan updated = plan.finishActiveTask(taskStatus,
            taskResult, taskError, System.currentTimeMillis());
        if (taskStatus != AgentTaskStatus.COMPLETED
            && parent.getAgent().getPlanningPolicy().getFailureStrategy()
            == AgentPlanningPolicy.FailureStrategy.STOP) {
            updated = updated.stop(taskError, System.currentTimeMillis());
        } else if (taskStatus != AgentTaskStatus.COMPLETED
            && canReplan(parent.getAgent().getPlanningPolicy(), updated)) {
            updated = updated.beginReplanning(System.currentTimeMillis());
        }
        parent.updateTaskPlan(updated);
        parent.addChildUsage(child);
        eventPublisher.notifyTaskFinished(parent, activeTask, child, taskStatus);
    }

    private List<AgentTask> parseTasks(AgentRun run, JSONArray values) {
        AgentPlanningPolicy policy = run.getAgent().getPlanningPolicy();
        List<AgentTask> tasks = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            JSONObject value = values.getJSONObject(index);
            String id = value == null ? null : value.getString("id");
            String title = value == null ? null : value.getString("title");
            String description = value == null ? null : value.getString("description");
            String assignedAgentId = value == null ? null : value.getString("assignedAgentId");
            if (!StringUtil.hasText(id) || !StringUtil.hasText(title)
                || !StringUtil.hasText(description)) {
                throw new IllegalArgumentException(
                    "each task requires id, title and description, index=" + index);
            }
            if (!policy.canDelegateTo(run.getAgent().getId(), assignedAgentId)) {
                throw new IllegalArgumentException("task agent is not allowed: " + assignedAgentId);
            }
            tasks.add(AgentTask.builder(title).id(id).description(description)
                .expectedOutput(value.getString("expectedOutput"))
                .assignedAgentId(assignedAgentId).position(index).build());
        }
        return tasks;
    }

    private void validateTaskAgent(AgentRun parent, AgentTask task, String childAgentId) {
        String expected = StringUtil.hasText(task.getAssignedAgentId())
            ? task.getAssignedAgentId() : parent.getAgent().getId();
        if (!expected.equals(childAgentId)
            || !parent.getAgent().getPlanningPolicy()
            .canDelegateTo(parent.getAgent().getId(), childAgentId)) {
            throw new IllegalArgumentException("task cannot be delegated to Agent: " + childAgentId);
        }
    }

    private String taskInput(AgentTaskPlan plan, AgentTask task) {
        return "总体目标：" + plan.getGoal()
            + "\n当前任务：" + task.getTitle()
            + "\n执行要求：" + task.getDescription()
            + (StringUtil.hasText(task.getExpectedOutput())
            ? "\n期望输出：" + task.getExpectedOutput() : "")
            + "\n请只完成当前任务，并返回可供父 Agent 汇总的结果。";
    }

    private String lastTaskResult(AgentTaskPlan plan) {
        List<AgentTask> tasks = plan.getTasks();
        for (int index = tasks.size() - 1; index >= 0; index--) {
            if (StringUtil.hasText(tasks.get(index).getResult())) return tasks.get(index).getResult();
        }
        return "任务计划已执行完成";
    }

    private boolean canReplan(AgentPlanningPolicy policy, AgentTaskPlan plan) {
        return policy.getMaxReplans() > plan.getRevisionCount()
            && (policy.isTaskRevisionAllowed() || policy.isTaskAppendAllowed())
            && plan.getNextTask() != null;
    }

    private String limitTaskResult(AgentRun parent, String value) {
        if (value == null) return null;
        int maxLength = parent.getAgent().getPlanningPolicy().getTaskResultMaxLength();
        if (maxLength <= 0 || value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "\n[子任务结果已截断，完整内容保留在子 Run 中]";
    }

    private JSONObject arguments(ToolCall call) {
        Map<String, Object> values = call.getArgsMap();
        return values == null ? new JSONObject() : new JSONObject(values);
    }

    private ToolMessage toolResult(ToolCall call, Object... values) {
        Map<String, Object> result = attributes("accepted", true);
        result.putAll(attributes(values));
        ToolMessage message = new ToolMessage();
        message.setToolCallId(callKey(call));
        message.setContent(JSON.toJSONString(result));
        return message;
    }

    private Map<String, Object> attributes(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            if (values[index] != null && values[index + 1] != null) {
                result.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return result;
    }

    private String callKey(ToolCall call) {
        return StringUtil.hasText(call.getId()) ? call.getId() : call.getName();
    }
}
