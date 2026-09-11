/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.agent;

import com.agentsflex.core.model.exception.ModelException;
import com.agentsflex.core.model.exception.ModelOverloadedException;
import com.agentsflex.core.model.exception.ModelQuotaExceededException;
import com.agentsflex.core.model.exception.ModelRateLimitException;
import com.agentsflex.core.model.exception.TokenLimitExceededException;

import java.io.Serializable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/**
 * 一次可持久化的模型故障记录。
 *
 * <p>{@code failureId} 同时作为模型挂起的 correlationId。调用方修复额度、限流、Token 上限或
 * 模型配置后，必须携带同一个 ID 提交恢复命令，防止迟到请求恢复另一轮模型故障。</p>
 */
public final class AgentModelFailure implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String failureId;
    private final AgentModelFailureType type;
    private final String exceptionType;
    private final String message;
    private final int httpStatus;
    private final String errorCode;
    private final String errorType;
    private final Long retryAfterMillis;
    private final TokenLimitExceededException.Phase tokenLimitPhase;
    private final int modelAttempt;
    private final long occurredAt;

    private AgentModelFailure(String failureId, AgentModelFailureType type,
                              String exceptionType, String message, int httpStatus,
                              String errorCode, String errorType, Long retryAfterMillis,
                              TokenLimitExceededException.Phase tokenLimitPhase,
                              int modelAttempt, long occurredAt) {
        this.failureId = failureId;
        this.type = type;
        this.exceptionType = exceptionType;
        this.message = message;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.errorType = errorType;
        this.retryAfterMillis = retryAfterMillis;
        this.tokenLimitPhase = tokenLimitPhase;
        this.modelAttempt = modelAttempt;
        this.occurredAt = occurredAt;
    }

    /**
     * 从异常 cause 链中提取第一个结构化模型异常。
     *
     * @param error        模型调用抛出的异常
     * @param modelAttempt 当前 Turn 已发起的模型调用次数
     * @return 可持久化故障；异常链中没有 ModelException 时返回 {@code null}
     */
    static AgentModelFailure from(Throwable error, int modelAttempt) {
        ModelException modelError = findModelException(error);
        if (modelError == null) return null;

        AgentModelFailureType type = AgentModelFailureType.UNAVAILABLE;
        int status = 0;
        String code = null;
        String modelErrorType = null;
        Long retryAfter = null;
        TokenLimitExceededException.Phase phase = null;
        if (modelError instanceof ModelQuotaExceededException) {
            ModelQuotaExceededException value = (ModelQuotaExceededException) modelError;
            type = AgentModelFailureType.QUOTA_EXCEEDED;
            status = value.getHttpStatus();
            code = value.getErrorCode();
            modelErrorType = value.getErrorType();
        } else if (modelError instanceof ModelRateLimitException) {
            ModelRateLimitException value = (ModelRateLimitException) modelError;
            type = AgentModelFailureType.RATE_LIMITED;
            status = value.getHttpStatus();
            code = value.getErrorCode();
            modelErrorType = value.getErrorType();
            retryAfter = value.getRetryAfterMillis();
        } else if (modelError instanceof TokenLimitExceededException) {
            TokenLimitExceededException value = (TokenLimitExceededException) modelError;
            type = AgentModelFailureType.TOKEN_LIMIT_EXCEEDED;
            status = value.getHttpStatus();
            code = value.getErrorCode();
            modelErrorType = value.getErrorType();
            phase = value.getPhase();
        } else if (modelError instanceof ModelOverloadedException) {
            ModelOverloadedException value = (ModelOverloadedException) modelError;
            type = AgentModelFailureType.OVERLOADED;
            status = value.getHttpStatus();
            code = value.getErrorCode();
            modelErrorType = value.getErrorType();
        }
        return new AgentModelFailure(UUID.randomUUID().toString(), type,
            modelError.getClass().getName(), modelError.getMessage(), status, code,
            modelErrorType, retryAfter, phase, Math.max(0, modelAttempt),
            System.currentTimeMillis());
    }

    private static ModelException findModelException(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(
            new IdentityHashMap<Throwable, Boolean>());
        Throwable current = error;
        ModelException fallback = null;
        while (current != null && visited.add(current)) {
            if (current instanceof ModelQuotaExceededException
                || current instanceof ModelRateLimitException
                || current instanceof TokenLimitExceededException
                || current instanceof ModelOverloadedException) {
                return (ModelException) current;
            }
            if (fallback == null && current instanceof ModelException) {
                fallback = (ModelException) current;
            }
            current = current.getCause();
        }
        return fallback;
    }

    AgentModelFailure copy() {
        return new AgentModelFailure(failureId, type, exceptionType, message, httpStatus,
            errorCode, errorType, retryAfterMillis, tokenLimitPhase, modelAttempt, occurredAt);
    }

    public String getFailureId() {
        return failureId;
    }

    public AgentModelFailureType getType() {
        return type;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public String getMessage() {
        return message;
    }

    /**
     * @return HTTP 状态码；底层响应未提供时为 0
     */
    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorType() {
        return errorType;
    }

    public Long getRetryAfterMillis() {
        return retryAfterMillis;
    }

    public TokenLimitExceededException.Phase getTokenLimitPhase() {
        return tokenLimitPhase;
    }

    public int getModelAttempt() {
        return modelAttempt;
    }

    public long getOccurredAt() {
        return occurredAt;
    }
}
