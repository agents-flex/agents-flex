/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.core.model.exception;

import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.Locale;

/**
 * 将不同模型供应商的错误响应归一化为可处理的模型异常。
 *
 * <p>供应商的 {@code error.code} 并不统一，因此分类优先使用明确错误码，再结合 HTTP 状态、
 * error.type 和错误消息。未知错误仍返回普通 {@link ModelException}。</p>
 */
public final class ModelErrorClassifier {

    private ModelErrorClassifier() {
    }

    /**
     * 将 ChatModel 错误响应转换为异常。
     */
    public static ModelException fromResponse(AiMessageResponse response) {
        if (response == null || !response.isError()) {
            return new ModelException("model response is not an error");
        }
        return fromError(0, response.getErrorCode(), response.getErrorType(),
            response.getErrorMessage(), null);
    }

    /**
     * 将 HTTP 错误响应转换为异常。响应体不是 JSON 时仍保留原始文本。
     */
    public static ModelException fromHttpError(int status, String body, Long retryAfterMillis) {
        String code = null;
        String type = null;
        String message = body;
        try {
            JSONObject root = JSON.parseObject(body);
            JSONObject error = root == null ? null : root.getJSONObject("error");
            if (error != null) {
                code = error.getString("code");
                type = error.getString("type");
                message = error.getString("message");
            }
        } catch (RuntimeException ignored) {
            // 保留原始 body，由下方的状态码和文本规则继续分类。
        }
        return fromError(status, code, type, message, retryAfterMillis);
    }

    /**
     * 根据供应商错误字段执行统一分类。
     */
    public static ModelException fromError(int status, String code, String type,
                                           String message, Long retryAfterMillis) {
        String normalizedCode = lower(code);
        String normalizedType = lower(type);
        String normalizedMessage = lower(message);

        if (isQuota(normalizedCode)) {
            return new ModelQuotaExceededException(message, status, code, type);
        }
        if (isRateLimit(status, normalizedCode, normalizedType, normalizedMessage)) {
            return new ModelRateLimitException(message, status, code, type, retryAfterMillis);
        }
        if (isOverloaded(status, normalizedCode, normalizedMessage)) {
            return new ModelOverloadedException(message, status, code, type);
        }
        if (isTokenLimit(status, normalizedCode, normalizedType, normalizedMessage)) {
            return new TokenLimitExceededException(message, status, code, type,
                TokenLimitExceededException.Phase.INPUT_CONTEXT);
        }
        return new ModelException(message == null ? "model request failed" : message);
    }

    private static boolean isQuota(String code) {
        return code.contains("credit_balance_exhausted")
            || code.contains("spend_limit_exceeded")
            || code.contains("usage_limit_exceeded")
            || code.contains("insufficient_quota")
            || code.contains("quota");
    }

    private static boolean isRateLimit(int status, String code, String type, String message) {
        if (isQuota(code)) return false;
        return status == 429
            || code.contains("rate_limit")
            || code.contains("ratelimit")
            || type.contains("rate_limit")
            || message.contains("rate limit")
            || message.contains("too many requests")
            || message.contains("请求过于频繁")
            || message.contains("请求频率");
    }

    private static boolean isOverloaded(int status, String code, String message) {
        return status == 503
            || code.contains("overload")
            || code.contains("slow_down")
            || message.contains("server is overloaded")
            || message.contains("temporarily unavailable")
            || message.contains("服务过载");
    }

    private static boolean isTokenLimit(int status, String code, String type, String message) {
        if (code.contains("context_length") || code.contains("token_limit")
            || code.contains("max_tokens") || code.contains("input_length")) {
            return true;
        }
        boolean requestError = status == 400 || status == 413
            || type.contains("invalid_request") || type.contains("bad_request")
            || code.contains("invalid_parameter");
        if (!requestError) return false;
        return message.contains("context length") || message.contains("maximum context")
            || message.contains("too many tokens") || message.contains("token limit")
            || message.contains("input length") || message.contains("prompt is too long")
            || message.contains("上下文") || message.contains("输入长度")
            || message.contains("输入 token") || message.contains("token 数量")
            || message.contains("maximum number of tokens");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
