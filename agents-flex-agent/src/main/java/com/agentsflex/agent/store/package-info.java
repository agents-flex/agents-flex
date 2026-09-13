/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

/**
 * AgentTurn 状态持久化与版本 CAS 接口。
 *
 * <p>存储实现负责 Snapshot 的乐观锁写入、可运行任务领取以及租约续期。
 * 默认内存实现适合本地执行和测试，长任务应使用数据库或其他持久化实现。</p>
 */
package com.agentsflex.agent.store;
