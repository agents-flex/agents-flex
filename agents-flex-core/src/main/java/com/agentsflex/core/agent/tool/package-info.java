/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

/**
 * Agent 工具解析、审批和错误处理策略。
 *
 * <p>工具注册表创建可持久化的 AgentToolReference，并通过 Agent 身份、工具 binding 和 metadata 恢复实际
 * Tool 对象；审批策略在执行副作用工具前返回允许、拒绝或等待人工审批的决定。</p>
 */
package com.agentsflex.core.agent.tool;
