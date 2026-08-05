/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

/**
 * 模型自主任务拆分、Turn 内计划状态、进度查询和顺序执行能力。
 *
 * <p>模型通过 Runner 提供的内置工具决定是否创建计划。计划与 AgentTurn 使用同一个 Snapshot，
 * 每个任务通过独立子 AgentTurn 执行，因此继续使用审批、重试、预算、Lease 和事件能力。</p>
 */
package com.agentsflex.agent.task;
