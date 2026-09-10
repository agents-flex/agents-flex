/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent.exception;

/**
 * Tool 主动请求暂停 AgentTurn 的统一控制流异常基类。
 *
 * <p>Runner 会沿异常 cause 链识别本类的已知具体子类型，因此 Middleware 或 Interceptor 可以补充
 * 上下文后重新包装异常，而不会把审批或表单请求误当成普通工具失败。该类型只定义“暂停”这一共同
 * 语义，不携带任何具体协议数据：人工审批由 {@link AgentApprovalRequiredException} 携带审批决定，
 * 表单输入由 {@link AgentFormRequiredException} 携带表单定义。</p>
 *
 * <p>业务 Tool 不应直接抛出本基类，而应根据暂停原因抛出对应的具体子类。该类型不表示故障，
 * Middleware、Interceptor 和业务代码均不应吞掉它。</p>
 */
public abstract class AgentToolSuspensionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * 仅供具体暂停子类设置便于日志和诊断的信息。
     *
     * @param message 暂停原因的可读描述
     */
    protected AgentToolSuspensionException(String message) {
        super(message);
    }
}
