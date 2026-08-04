/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

/**
 * Agent 执行事件与统一监听接口。
 *
 * <p>Framework 只负责在当前进程内发布不可变事件。数据库、消息队列、审计和可靠投递由业务监听器
 * 自行实现。</p>
 */
package com.agentsflex.agent.event;
