/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

/**
 * Agent 工具解析、审批和错误处理策略。
 *
 * <p>AgentToolInvocation 向工具传递当前 Turn、Agent 和 ToolCall 身份；审批策略在执行副作用工具前
 * 返回允许、拒绝或等待人工审批的决定。</p>
 */
package com.agentsflex.agent.tool;
