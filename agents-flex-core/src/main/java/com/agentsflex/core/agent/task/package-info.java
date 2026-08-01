/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

/**
 * 复杂目标的任务拆分、计划持久化、进度查询和顺序执行能力。
 *
 * <p>任务计划不替代 AgentRun。每个任务通过独立子 AgentRun 执行，因此可以继续使用工具审批、
 * Checkpoint、错误重试、预算、Worker Lease 和事件持久化等运行时能力。</p>
 */
package com.agentsflex.core.agent.task;
