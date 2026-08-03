/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

/**
 * AgentRun 生命周期事件的追加式持久化与增量读取接口。
 *
 * <p>事件流与 Snapshot 分开保存：Snapshot 表示当前状态，事件流表示状态如何演进，适合实时推送、
 * 审计、追踪以及消费者断点续读。</p>
 */
package com.agentsflex.agent.event;
