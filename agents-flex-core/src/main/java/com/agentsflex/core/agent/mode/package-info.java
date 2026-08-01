/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

/**
 * Agent 可插拔运行模式及其受控执行上下文。
 *
 * <p>默认模式使用模型原生 ToolCall 闭环。平台可以实现其他模式，并通过 modeState 和 Checkpoint
 * 保存可恢复的模式中间状态。</p>
 */
package com.agentsflex.core.agent.mode;
