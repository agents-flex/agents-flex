/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

/**
 * Agents-Flex 的现代 Agent 运行时。
 *
 * <p>默认运行模式使用模型原生 ToolCall 协议实现“模型决策 - 工具执行 - 结果回传 - 再次决策”的闭环。
 * 业务可以通过 execution mode SPI 扩展规划、复核或领域专用的执行策略。</p>
 *
 * <p>主要类型及职责：</p>
 * <ul>
 *     <li>{@link com.agentsflex.agent.Agent}：不可变的能力定义，描述模型、指令、工具和策略；</li>
 *     <li>{@link com.agentsflex.agent.AgentRun}：单次任务的可变状态，保存消息、状态和执行结果；</li>
 *     <li>{@link com.agentsflex.agent.AgentRunner}：管理公共生命周期并保存稳定状态；</li>
 *     <li>{@link com.agentsflex.agent.AgentInvocationContext}：携带单次调用身份和非持久化服务对象；</li>
 *     <li>{@link com.agentsflex.agent.middleware.AgentMiddleware}：包装步骤、模型和工具调用；</li>
 *     <li>{@link com.agentsflex.agent.mode.AgentExecutionMode}：定义一次运行步骤的推进逻辑；</li>
 *     <li>{@link com.agentsflex.agent.store.AgentRunStore}：保存 Checkpoint、领取任务和管理 Worker 租约；</li>
 *     <li>{@link com.agentsflex.agent.event.AgentRunEventStore}：追加执行事件并支持按序号增量读取；</li>
 *     <li>{@link com.agentsflex.agent.event.AgentRuntimeEventStream}：发布模型增量和工具进度等实时事件；</li>
 *     <li>{@link com.agentsflex.agent.command.AgentRunCommandStore}：持久化审批和恢复命令；</li>
 *     <li>{@link com.agentsflex.agent.context.AgentArtifactStore}：保存大型工具结果；</li>
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
