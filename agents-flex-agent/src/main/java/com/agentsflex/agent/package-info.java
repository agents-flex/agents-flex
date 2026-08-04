/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

/**
 * Agents-Flex 的现代 Agent 运行时。
 *
 * <p>Runner 使用模型原生 ToolCall 协议实现“模型决策 - 工具执行 - 结果回传 - 再次决策”的闭环，
 * 业务可以通过 Middleware 包装 step、模型调用和工具调用。</p>
 *
 * <p>主要类型及职责：</p>
 * <ul>
 *     <li>{@link com.agentsflex.agent.Agent}：不可变的能力定义，描述模型、指令、工具和策略；</li>
 *     <li>{@link com.agentsflex.agent.AgentRun}：单次任务对象，组合可持久化状态与进程内运行属性；</li>
 *     <li>{@link com.agentsflex.agent.AgentRunState}：Run 与 Snapshot 共享的可序列化状态定义；</li>
 *     <li>{@link com.agentsflex.agent.AgentRunSnapshot}：Agent 标识与不可变运行状态组成的持久化快照；</li>
 *     <li>{@link com.agentsflex.agent.AgentRunner}：管理公共生命周期并保存稳定状态；</li>
 *     <li>{@link com.agentsflex.agent.middleware.AgentMiddleware}：包装步骤、模型调用和工具调用；</li>
 *     <li>{@link com.agentsflex.agent.store.AgentRunStore}：保存 Snapshot、领取任务和管理 Worker 租约；</li>
 *     <li>{@link com.agentsflex.agent.event.AgentEventListener}：观察生命周期、模型增量和工具进度事件；</li>
 *     <li>{@link com.agentsflex.agent.task.AgentPlanningPolicy}：约束模型自主创建和执行任务计划；</li>
 *     <li>{@link com.agentsflex.agent.loader.AgentLoader}：根据稳定 ID 和版本加载可执行 Agent；</li>
 *     <li>{@link com.agentsflex.agent.tool.AgentToolInvocation}：向工具提供 Run 身份和稳定幂等键。</li>
 * </ul>
 *
 * <p>最简调用方式：</p>
 * <pre>
 * Agent agent = Agent.builder("assistant")
 *     .instructions("请准确回答用户问题")
 *     .chatModel(chatModel)
 *     .tool(weatherTool)
 *     .build();
 *
 * AgentRun run = new AgentRunner().run(agent, "上海今天天气如何？");
 * String output = run.getFinalOutput();
 * </pre>
 *
 * <p>需要由业务系统逐步控制、记录中间状态或在步骤之间执行额外逻辑时，可以使用：</p>
 * <pre>
 * AgentRun run = runner.start(agent, userInput);
 * while (!run.getStatus().isTerminal() &amp;&amp; !run.getStatus().isBlocked()) {
 *     AgentStepResult stepResult = runner.step(run);
 *     // 持久化状态、刷新 UI 或检查取消条件
 * }
 * </pre>
 */
package com.agentsflex.agent;
