package com.agentsflex.core.model.exception;

/**
 * 模型服务暂时限制了请求或 Token 速率，通常可以按 Retry-After 退避重试。
 */
public class ModelRateLimitException extends ModelException {

    private final int httpStatus;
    private final String errorCode;
    private final String errorType;
    private final Long retryAfterMillis;

    public ModelRateLimitException(String message, int httpStatus, String errorCode,
                                   String errorType, Long retryAfterMillis) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.errorType = errorType;
        this.retryAfterMillis = retryAfterMillis;
    }

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
}
