/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

/**
 * Agent 定义注册与解析接口。
 *
 * <p>Checkpoint 保存稳定的 agentId 和 agentVersion。任务在其他进程恢复时，通过本包接口精确绑定
 * 模型、工具、拦截器和执行策略等不可序列化对象。</p>
 */
package com.agentsflex.core.agent.registry;
