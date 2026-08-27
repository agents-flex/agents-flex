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
 * <p>该类是包内实现细节，不是新的公开扩展点。它负责解析内置规划工具、推进计划、关联父子 Turn、
 * 回写子任务结果以及准备规划工具；Snapshot、租约和普通运行循环仍由 AgentRunner 统一管理。</p>
 */
final class AgentRunnerPlanning {

    private final AgentRunner runner;
    private final AgentLoader agentLoader;
    private final AgentEventPublisher eventPublisher;

    /**
     * 创建任务规划协调器。
     *
     * @param runner         负责 Turn 生命周期和持久化的 Runner
     * @param agentLoader    用于加载允许委派的子 Agent
     * @param eventPublisher 规划生命周期事件发布器
     */
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
     * 应用规划 ToolCall，只修改 Turn 中的计划状态并返回对应 ToolMessage。
     * 调用方应先保存 ToolMessage 和 Snapshot，再调用 {@link #notifyPlanChanged(AgentTurn, ToolCall)}。
     */
    ToolMessage applyToolCall(AgentTurn turn, ToolCall call) {
        return AgentPlanningTool.NAME.equals(call.getName())
            ? createTaskPlan(turn, call) : updateTaskPlan(turn, call);
    }

    /**
     * 在规划 ToolCall 的状态已经保存后发布相应生命周期事件。
     */
    void notifyPlanChanged(AgentTurn turn, ToolCall call) {
        if (AgentPlanningTool.NAME.equals(call.getName())) {
            eventPublisher.notifyPlanCreated(turn, turn.getTaskPlan());
        } else {
            eventPublisher.notifyPlanUpdated(turn, turn.getTaskPlan());
        }
    }

    /**
     * 优先推进 Turn 中已经存在的任务计划。
     *
     * @return 规划产生独立动作时返回步骤结果；无需拦截普通模型步骤时返回 {@code null}
     */
    AgentStepResult advance(AgentTurn turn) {
        AgentTaskPlan plan = turn.getTaskPlan();
        if (plan == null || plan.getStatus() == AgentTaskPlanStatus.COMPLETED
            || plan.getStatus() == AgentTaskPlanStatus.FINALIZING
            || plan.getStatus() == AgentTaskPlanStatus.REPLANNING) return null;
        if (plan.getActiveTask() != null) {
            return AgentStepResult.of(null, null, null);
        }
        AgentTask next = plan.getNextTask();
        if (next == null) {
            turn.updateTaskPlan(plan.beginFinalizing(System.currentTimeMillis()));
            runner.saveSnapshot(turn);
            if (!turn.getAgent().getPlanningPolicy().isFinalSummaryRequired()) {
                AiMessage finalMessage = new AiMessage(lastTaskResult(plan));
                turn.getPrompt().addMessage(finalMessage);
                return runner.complete(turn, null, finalMessage);
            }
            return null;
        }
        String agentId = StringUtil.hasText(next.getAssignedAgentId())
            ? next.getAssignedAgentId() : turn.getAgent().getId();
        runner.startChild(turn, agentId, taskInput(plan, next));
        return AgentStepResult.of(null, null, null);
    }

    /**
     * 在父子 Snapshot 保存前，把新建子 Turn 与当前计划任务关联。
     */
    void bindChild(AgentTurn parent, AgentTurn child, String childAgentId) {
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
    void notifyTaskStarted(AgentTurn parent, AgentTurn child) {
        AgentTaskPlan plan = parent.getTaskPlan();
        if (plan != null && plan.getActiveTask() != null) {
            eventPublisher.notifyTaskStarted(parent, plan.getActiveTask(), child);
        }
    }

    /**
     * 将终止子 Turn 的结果写回计划并恢复父 Turn。
     * 重复处理已恢复的父 Turn 时不会再次追加结果。
     */
    AgentTurn resumeParentFromChild(AgentTurn child) {
        if (child == null || !child.getStatus().isTerminal()
            || !StringUtil.hasText(child.getParentTurnId())) {
            return null;
        }
        AgentTurn parent = runner.restore(child.getParentTurnId());
        AgentSuspension suspension = parent.getSuspension();
        if (parent.getStatus() != AgentTurnStatus.WAITING_FOR_CHILD || suspension == null
            || !child.getId().equals(suspension.getCorrelationId())) {
            return parent;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("childTurnId", child.getId());
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
     * 查询计划以及当前计划子 Turn 的真实运行状态。
     */
    AgentTaskProgress getTaskProgress(String turnId) {
        AgentTurn root = runner.restore(turnId);
        AgentTaskPlan plan = root.getTaskPlan();
        if (plan == null) return null;
        AgentTurn child = currentChild(root);
        return new AgentTaskProgress(plan,
            child == null ? root.getStatus() : child.getStatus(),
            child == null ? root.getSuspension() : child.getSuspension());
    }

    /**
     * 返回父 Turn 当前计划关联的子 Turn；没有活动计划任务时返回 {@code null}。
     */
    AgentTurn currentChild(AgentTurn parent) {
        if (parent == null || parent.getStatus() != AgentTurnStatus.WAITING_FOR_CHILD) return null;
        AgentTaskPlan plan = parent.getTaskPlan();
        AgentTask task = plan == null ? null : plan.getActiveTask();
        return task == null || !StringUtil.hasText(task.getChildTurnId())
            ? null : runner.restore(task.getChildTurnId());
    }

    /**
     * 加载白名单中的完整 Agent，并向当前 Turn 的 Prompt 装配规划工具。
     */
    void prepareTools(AgentTurn turn) {
        if (turn.isPlanningToolsPrepared()) return;
        List<Agent> delegates = new ArrayList<>();
        for (String agentId : turn.getAgent().getPlanningPolicy().getAllowedAgentIds()) {
            Agent delegate = agentLoader.loadActive(agentId);
            if (delegate == null) {
                throw new IllegalStateException(
                    "Allowed planning Agent cannot be loaded: " + agentId);
            }
            delegates.add(delegate);
        }
        turn.preparePlanningTools(delegates);
    }

    /**
     * 在 Turn 完成时收束重规划或最终汇总状态。
     */
    void finishPlan(AgentTurn turn) {
        AgentTaskPlan plan = turn.getTaskPlan();
        if (plan != null && plan.getStatus() == AgentTaskPlanStatus.REPLANNING) {
            plan = plan.stop("模型未提交计划调整，剩余任务已跳过", System.currentTimeMillis());
        }
        if (plan != null && plan.getStatus() == AgentTaskPlanStatus.FINALIZING) {
            turn.updateTaskPlan(plan.complete(System.currentTimeMillis()));
        }
    }

    /**
     * 解析模型提交的计划创建调用，校验策略限制并写入 Turn。
     *
     * @param turn 开启规划能力且尚未创建计划的 Turn
     * @param call {@link AgentPlanningTool#NAME} 工具调用
     * @return 向模型确认计划 ID 和任务数量的 ToolMessage
     * @throws IllegalArgumentException 规划未启用、计划已存在或参数不符合策略时抛出
     */
    private ToolMessage createTaskPlan(AgentTurn turn, ToolCall call) {
        if (!turn.isPlanningEnabled()) {
            throw new IllegalArgumentException("task planning is not enabled for this turn");
        }
        if (turn.getTaskPlan() != null) {
            throw new IllegalArgumentException("an AgentTurn can only create one task plan");
        }
        JSONObject object = arguments(call);
        String goal = object.getString("goal");
        JSONArray values = object.getJSONArray("tasks");
        AgentPlanningPolicy policy = turn.getAgent().getPlanningPolicy();
        if (!StringUtil.hasText(goal) || values == null || values.isEmpty()) {
            throw new IllegalArgumentException("planning goal and tasks are required");
        }
        if (values.size() > policy.getMaxTasks()) {
            throw new IllegalArgumentException("task count exceeds maxTasks: "
                + policy.getMaxTasks());
        }
        List<AgentTask> tasks = parseTasks(turn, values);
        AgentTaskPlan plan = AgentTaskPlan.create(goal, tasks);
        turn.updateTaskPlan(plan);
        return toolResult(call, "planId", plan.getId(), "taskCount", tasks.size());
    }

    /**
     * 在重规划阶段用模型提交的新定义调整剩余任务。
     *
     * @param turn 当前处于 REPLANNING 的父 Turn
     * @param call 计划更新工具调用
     * @return 包含修订次数和剩余任务数的确认消息
     * @throws IllegalArgumentException 当前状态、修订次数或更新内容不合法时抛出
     */
    private ToolMessage updateTaskPlan(AgentTurn turn, ToolCall call) {
        AgentTaskPlan plan = turn.getTaskPlan();
        AgentPlanningPolicy policy = turn.getAgent().getPlanningPolicy();
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
        AgentTaskPlan updated = plan.revisePending(parseTasks(turn, values), reason,
            policy.isTaskRevisionAllowed(), policy.isTaskAppendAllowed(),
            policy.getMaxTasks(), System.currentTimeMillis());
        turn.updateTaskPlan(updated);
        return toolResult(call, "planId", updated.getId(),
            "revisionCount", updated.getRevisionCount(), "pendingTaskCount", values.size());
    }

    /**
     * 把子 Turn 终态映射为计划任务终态，并按失败策略推进、停止或进入重规划。
     *
     * @param parent     持有任务计划的父 Turn
     * @param child      已结束的子 Turn
     * @param taskResult 已按策略截断的子任务输出
     */
    private void finishActiveTask(AgentTurn parent, AgentTurn child, String taskResult) {
        AgentTaskPlan plan = parent.getTaskPlan();
        if (plan == null || plan.getActiveTask() == null
            || !child.getId().equals(plan.getActiveTask().getChildTurnId())) return;
        AgentTask activeTask = plan.getActiveTask();
        AgentTaskStatus taskStatus = child.getStatus() == AgentTurnStatus.COMPLETED
            ? AgentTaskStatus.COMPLETED : (child.getStatus() == AgentTurnStatus.CANCELLED
            ? AgentTaskStatus.CANCELLED : AgentTaskStatus.FAILED);
        String taskError = child.getError() == null
            ? (child.getStatus() == AgentTurnStatus.COMPLETED ? null : child.getStatus().name())
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

    /**
     * 将规划工具的 JSON 数组转换为经过委派白名单校验的任务定义。
     *
     * @param turn   提供规划策略和当前 Agent ID 的 Turn
     * @param values 模型提交的任务数组
     * @return 保持输入顺序并写入 position 的任务列表
     * @throws IllegalArgumentException 必填字段缺失或目标 Agent 不允许委派时抛出
     */
    private List<AgentTask> parseTasks(AgentTurn turn, JSONArray values) {
        AgentPlanningPolicy policy = turn.getAgent().getPlanningPolicy();
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
            if (!policy.canDelegateTo(turn.getAgent().getId(), assignedAgentId)) {
                throw new IllegalArgumentException("task agent is not allowed: " + assignedAgentId);
            }
            tasks.add(AgentTask.builder(title).id(id).description(description)
                .expectedOutput(value.getString("expectedOutput"))
                .assignedAgentId(assignedAgentId).position(index).build());
        }
        return tasks;
    }

    /**
     * 校验实际启动的子 Agent 与任务定义及父 Agent 委派策略一致。
     *
     * @param parent       父 Turn
     * @param task         即将执行的计划任务
     * @param childAgentId 实际子 Agent ID
     * @throws IllegalArgumentException Agent 不匹配或不在允许范围时抛出
     */
    private void validateTaskAgent(AgentTurn parent, AgentTask task, String childAgentId) {
        String expected = StringUtil.hasText(task.getAssignedAgentId())
            ? task.getAssignedAgentId() : parent.getAgent().getId();
        if (!expected.equals(childAgentId)
            || !parent.getAgent().getPlanningPolicy()
            .canDelegateTo(parent.getAgent().getId(), childAgentId)) {
            throw new IllegalArgumentException("task cannot be delegated to Agent: " + childAgentId);
        }
    }

    /**
     * 生成传给子 Agent 的受限任务提示，包含总体目标、当前任务和期望输出。
     *
     * @param plan 父计划
     * @param task 当前任务
     * @return 子 Agent 的用户输入文本
     */
    private String taskInput(AgentTaskPlan plan, AgentTask task) {
        return "总体目标：" + plan.getGoal()
            + "\n当前任务：" + task.getTitle()
            + "\n执行要求：" + task.getDescription()
            + (StringUtil.hasText(task.getExpectedOutput())
            ? "\n期望输出：" + task.getExpectedOutput() : "")
            + "\n请只完成当前任务，并返回可供父 Agent 汇总的结果。";
    }

    /**
     * 从后向前查找最后一个非空任务结果，供无需模型汇总的计划直接返回。
     *
     * @param plan 已执行到末尾的计划
     * @return 最近任务结果；均为空时返回稳定的完成提示
     */
    private String lastTaskResult(AgentTaskPlan plan) {
        List<AgentTask> tasks = plan.getTasks();
        for (int index = tasks.size() - 1; index >= 0; index--) {
            if (StringUtil.hasText(tasks.get(index).getResult())) return tasks.get(index).getResult();
        }
        return "任务计划已执行完成";
    }

    /**
     * 判断失败后的计划是否仍满足重规划次数、能力和剩余任务条件。
     *
     * @param policy 当前规划策略
     * @param plan   已记录任务失败的计划
     * @return 可以进入 REPLANNING 时返回 {@code true}
     */
    private boolean canReplan(AgentPlanningPolicy policy, AgentTaskPlan plan) {
        return policy.getMaxReplans() > plan.getRevisionCount()
            && (policy.isTaskRevisionAllowed() || policy.isTaskAppendAllowed())
            && plan.getNextTask() != null;
    }

    /**
     * 按父 Agent 策略限制回填到父上下文的子任务结果长度。
     *
     * @param parent 父 Turn
     * @param value  子 Turn 完整输出
     * @return 原值、截断并带提示的值，或 {@code null}
     */
    private String limitTaskResult(AgentTurn parent, String value) {
        if (value == null) return null;
        int maxLength = parent.getAgent().getPlanningPolicy().getTaskResultMaxLength();
        if (maxLength <= 0 || value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "\n[子任务结果已截断，完整内容保留在子 Turn 中]";
    }

    /**
     * 将工具调用参数转换为便于类型读取的 JSONObject。
     *
     * @param call 工具调用
     * @return 参数对象；调用没有参数时返回空对象
     */
    private JSONObject arguments(ToolCall call) {
        Map<String, Object> values = call.getArgsMap();
        return values == null ? new JSONObject() : new JSONObject(values);
    }

    /**
     * 构造规划工具的 JSON 成功响应，并绑定稳定 Tool Call ID。
     *
     * @param call   原始工具调用
     * @param values 追加到响应中的键值对
     * @return 可直接加入 Prompt 的 ToolMessage
     */
    private ToolMessage toolResult(ToolCall call, Object... values) {
        Map<String, Object> result = attributes("accepted", true);
        result.putAll(attributes(values));
        ToolMessage message = new ToolMessage();
        message.setToolCallId(callKey(call));
        message.setContent(JSON.toJSONString(result));
        return message;
    }

    /**
     * 把交替出现的键和值组装为有序 Map，忽略不完整或含空值的键值对。
     *
     * @param values 交替排列的键和值
     * @return 保持参数顺序的属性 Map
     */
    private Map<String, Object> attributes(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            if (values[index] != null && values[index + 1] != null) {
                result.put(String.valueOf(values[index]), values[index + 1]);
            }
        }
        return result;
    }

    /**
     * 返回工具结果关联键，优先使用模型生成的调用 ID，缺失时退化为工具名。
     *
     * @param call 工具调用
     * @return 非空关联键
     */
    private String callKey(ToolCall call) {
        return StringUtil.hasText(call.getId()) ? call.getId() : call.getName();
    }
}
