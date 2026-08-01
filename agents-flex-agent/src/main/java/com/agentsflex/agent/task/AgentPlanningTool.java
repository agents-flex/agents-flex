/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.task;

import com.agentsflex.agent.Agent;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据规划策略生成模型可见的稳定工具协议。
 *
 * <p>工具对象不执行外部函数，AgentRunner 会识别固定工具名并完成参数校验、Checkpoint 和任务调度。
 * 工具 schema 是模型与 Runner 之间的结构化协议，业务工具审批、中间件和 ToolInterceptor 不会处理
 * 这些内置状态转换。</p>
 *
 * <p>委派目标必须先由 AgentLoader 加载为完整 Agent。工具只向模型公开稳定 ID、名称和 description，
 * 不公开 Agent 内部 instructions 或平台 attributes。</p>
 */
public final class AgentPlanningTool {

    /**
     * 创建初始任务计划的稳定工具名。
     */
    public static final String NAME = "create_task_plan";
    /**
     * 调整尚未执行任务的稳定工具名。
     */
    public static final String UPDATE_NAME = "update_task_plan";

    private AgentPlanningTool() {
    }

    /**
     * 根据当前 Agent、已加载的委派目标和策略创建模型本轮可见的规划工具。
     */
    public static List<Tool> createTools(Agent currentAgent, List<Agent> delegates,
                                         AgentPlanningPolicy policy) {
        List<Tool> tools = new ArrayList<>();
        tools.add(createPlanTool(currentAgent, delegates, policy));
        if (policy.getMaxReplans() > 0
            && (policy.isTaskRevisionAllowed() || policy.isTaskAppendAllowed())) {
            tools.add(updatePlanTool(currentAgent, delegates, policy));
        }
        return tools;
    }

    /**
     * 创建初始计划工具，并把最大任务数和领域约束写入模型可见说明。
     */
    private static Tool createPlanTool(Agent currentAgent, List<Agent> delegates,
                                       AgentPlanningPolicy policy) {
        StringBuilder description = new StringBuilder(
            "仅当目标需要多个可独立执行的步骤时创建顺序任务计划；简单对话或单步任务直接回答。"
                + "任务数不得超过 " + policy.getMaxTasks() + "。")
            .append(delegateDescription(currentAgent, delegates));
        if (StringUtil.hasText(policy.getPlanningInstructions())) {
            description.append(" 规划要求：").append(policy.getPlanningInstructions());
        }
        return Tool.builder(NAME, description.toString())
            .addParameter(Parameter.builder().name("goal").type("string")
                .description("需要完成的总体目标").required(true).build())
            .addParameter(tasksParameter(currentAgent, delegates,
                "按执行顺序排列的完整任务列表"))
            .metadata("agent.internal", true)
            .metadata("agent.capability", "task-planning")
            .function(args -> null)
            .build();
    }

    /**
     * 按重规划开关创建仅允许调整待执行任务的工具。
     */
    private static Tool updatePlanTool(Agent currentAgent, List<Agent> delegates,
                                       AgentPlanningPolicy policy) {
        String operation = policy.isTaskRevisionAllowed() && policy.isTaskAppendAllowed()
            ? "修改或追加" : (policy.isTaskRevisionAllowed() ? "修改" : "追加");
        StringBuilder description = new StringBuilder(
            "仅在已有任务失败且需要调整后续计划时，").append(operation)
            .append("尚未开始的任务；已经执行的任务不可修改。单个计划最多调整 ")
            .append(policy.getMaxReplans()).append(" 次。")
            .append(delegateDescription(currentAgent, delegates));
        if (StringUtil.hasText(policy.getPlanningInstructions())) {
            description.append(" 规划要求：").append(policy.getPlanningInstructions());
        }
        return Tool.builder(UPDATE_NAME, description.toString())
            .addParameter(Parameter.builder().name("reason").type("string")
                .description("本次调整计划的原因").required(true).build())
            .addParameter(tasksParameter(currentAgent, delegates,
                "调整后全部尚未执行的任务，按新的执行顺序排列"))
            .metadata("agent.internal", true)
            .metadata("agent.capability", "task-replanning")
            .function(args -> null)
            .build();
    }

    /**
     * 创建 create 和 update 共用的任务对象数组参数。
     */
    private static Parameter tasksParameter(Agent currentAgent, List<Agent> delegates,
                                            String description) {
        Parameter.Builder assignedAgent = Parameter.builder().name("assignedAgentId").type("string")
            .description("可选的目标 Agent ID；省略时使用当前 Agent");
        List<String> agentIds = new ArrayList<>();
        if (currentAgent != null) agentIds.add(currentAgent.getId());
        if (delegates != null) {
            for (Agent delegate : delegates) agentIds.add(delegate.getId());
        }
        if (!agentIds.isEmpty()) assignedAgent.enums(agentIds.toArray(new String[0]));

        Parameter taskItem = Parameter.builder().type("object")
            .addChild(Parameter.builder().name("id").type("string")
                .description("任务在当前计划中的唯一稳定 ID").required(true).build())
            .addChild(Parameter.builder().name("title").type("string")
                .description("简短的任务标题").required(true).build())
            .addChild(Parameter.builder().name("description").type("string")
                .description("可独立执行的任务要求").required(true).build())
            .addChild(Parameter.builder().name("expectedOutput").type("string")
                .description("期望子 Agent 返回的结果内容或格式").build())
            .addChild(assignedAgent.build())
            .build();
        return Parameter.builder().name("tasks").type("array")
            .description(description).required(true).itemsParameter(taskItem).build();
    }

    /**
     * 将可委派 Agent 的能力说明写入模型可见的工具描述。
     */
    private static String delegateDescription(Agent currentAgent, List<Agent> delegates) {
        StringBuilder value = new StringBuilder(" 当前 Agent：")
            .append(agentDescription(currentAgent)).append('。');
        if (delegates != null && !delegates.isEmpty()) {
            value.append(" 可委派 Agent：");
            for (Agent delegate : delegates)
                value.append('[')
                    .append(agentDescription(delegate)).append("] ");
        }
        return value.toString();
    }

    /**
     * 将完整 Agent 转换为模型可见的稳定 ID、名称和公开能力说明。
     */
    private static String agentDescription(Agent agent) {
        if (agent == null) return "未定义";
        StringBuilder value = new StringBuilder(agent.getId())
            .append(" / ").append(agent.getName());
        if (StringUtil.hasText(agent.getDescription())) {
            value.append("：").append(agent.getDescription());
        }
        return value.toString();
    }
}
