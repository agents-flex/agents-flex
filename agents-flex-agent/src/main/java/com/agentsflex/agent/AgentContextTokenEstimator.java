/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.core.message.Message;

import java.util.List;

/**
 * 估算一组模型消息占用的 Token 数。
 */
@FunctionalInterface
public interface AgentContextTokenEstimator {
    /**
     * 估算即将发送给模型的消息列表所占用的 Token 数。
     *
     * <p>估算结果只用于窗口裁剪，不会写回消息或修改 Prompt。实现可以使用供应商提供的
     * tokenizer，也可以使用业务侧的近似算法；必须返回非负数。估算器返回负数会被 Runner
     * 视为配置错误并立即拒绝本次模型调用。</p>
     *
     * @param messages 按模型发送顺序排列的消息副本
     * @return 非负 Token 估算值
     */
    long estimate(List<Message> messages);
}
