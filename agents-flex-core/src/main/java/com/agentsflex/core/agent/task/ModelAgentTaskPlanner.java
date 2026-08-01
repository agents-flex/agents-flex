/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.core.agent.task;

import com.agentsflex.core.agent.Agent;
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.message.SystemMessage;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.SimplePrompt;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 使用 Agent 的聊天模型生成结构化任务列表的规划器。 */
public final class ModelAgentTaskPlanner implements AgentTaskPlanner {

    private static final String SYSTEM_INSTRUCTIONS =
        "你是任务规划器。请将用户目标拆分为少量、可独立执行、按顺序排列的任务。"
            + "只返回 JSON 对象，不要返回 Markdown。JSON 格式为："
            + "{\"tasks\":[{\"id\":\"任务 ID\","
            + "\"parentTaskId\":\"可选的父任务 ID\","
            + "\"title\":\"任务标题\","
            + "\"description\":\"执行要求\","
            + "\"assignedAgentId\":\"可选的 Agent ID\"}]}。";

    private final int maxTasks;

    public ModelAgentTaskPlanner() {
        this(10);
    }

    public ModelAgentTaskPlanner(int maxTasks) {
        if (maxTasks <= 0) {
            throw new IllegalArgumentException("maxTasks must be greater than 0");
        }
        this.maxTasks = maxTasks;
    }

    @Override
    public AgentTaskPlan createPlan(Agent agent, AgentPlanningContext context) {
        if (agent == null || context == null || !StringUtil.hasText(context.getGoal())) {
            throw new IllegalArgumentException("agent and planning goal are required");
        }
        SimplePrompt prompt = new SimplePrompt(context.getGoal());
        prompt.setSystemMessage(new SystemMessage(SYSTEM_INSTRUCTIONS));
        AiMessageResponse response = agent.getChatModel().chat(prompt, agent.getChatOptions());
        if (response == null || response.isError() || response.getMessage() == null) {
            throw new IllegalStateException("task planner model returned an invalid response");
        }
        return parsePlan(context.getGoal(), response.getMessage());
    }

    private AgentTaskPlan parsePlan(String goal, AiMessage message) {
        String content = message.getTextContent();
        if (!StringUtil.hasText(content)) {
            throw new IllegalStateException("task planner model returned empty content");
        }
        JSONObject root;
        try {
            root = JSON.parseObject(extractJsonObject(content));
        } catch (RuntimeException error) {
            throw new IllegalStateException("task planner model returned invalid JSON", error);
        }
        JSONArray taskArray = root.getJSONArray("tasks");
        if (taskArray == null || taskArray.isEmpty()) {
            throw new IllegalStateException("task planner model returned no tasks");
        }
        List<AgentTask> tasks = new ArrayList<>();
        int count = Math.min(taskArray.size(), maxTasks);
        for (int i = 0; i < count; i++) {
            JSONObject item = taskArray.getJSONObject(i);
            String title = item == null ? null : item.getString("title");
            if (!StringUtil.hasText(title)) {
                throw new IllegalStateException("task title must not be blank, index=" + i);
            }
            tasks.add(AgentTask.builder(title)
                .id(StringUtil.hasText(item.getString("id"))
                    ? item.getString("id") : java.util.UUID.randomUUID().toString())
                .parentTaskId(item.getString("parentTaskId"))
                .description(item.getString("description"))
                .assignedAgentId(item.getString("assignedAgentId"))
                .position(i)
                .build());
        }
        return new AgentTaskPlan(goal, tasks);
    }

    /** 兼容模型偶尔在 JSON 前后附加说明或代码围栏的情况。 */
    private String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("JSON object not found");
        }
        return content.substring(start, end + 1);
    }
}
