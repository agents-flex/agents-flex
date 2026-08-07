package com.agentsflex.core.model.exception;

/**
 * 模型服务拒绝了超过 Token 或上下文长度限制的请求。
 */
public class TokenLimitExceededException extends ModelException {

    public enum Phase {INPUT_CONTEXT, OUTPUT, TOTAL_CONTEXT, UNKNOWN}

    private final int httpStatus;
    private final String errorCode;
    private final String errorType;
    private final Phase phase;

    public TokenLimitExceededException(String message, int httpStatus, String errorCode,
                                       String errorType, Phase phase) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.errorType = errorType;
        this.phase = phase == null ? Phase.UNKNOWN : phase;
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

    public Phase getPhase() {
        return phase;
    }
}
