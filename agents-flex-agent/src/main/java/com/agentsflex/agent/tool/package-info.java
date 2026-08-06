/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

/**
 * Agent 工具解析、审批和错误处理策略。
 *
 * <p>AgentToolContext 向工具提供当前 Turn、Agent 和 ToolCall 的稳定身份，以及进度上报和动态取消
 * 检查及已提交的恢复表单数据；它不会暴露 Runner 状态机或可变 Turn。工具可以在产生副作用前抛出
 * AgentFormRequiredException 请求表单输入，审批策略则在执行副作用工具前返回允许、拒绝或等待
 * 人工审批的决定。</p>
 */
package com.agentsflex.agent.tool;
