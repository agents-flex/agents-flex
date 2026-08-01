/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

/**
 * AgentRun 状态持久化与 Worker 租约接口。
 *
 * <p>存储实现负责 Checkpoint 的乐观锁写入、父子任务原子保存、可运行任务领取以及租约续期。
 * 默认内存实现适合本地执行和测试，长任务应使用数据库或其他持久化实现。</p>
 */
package com.agentsflex.agent.store;
