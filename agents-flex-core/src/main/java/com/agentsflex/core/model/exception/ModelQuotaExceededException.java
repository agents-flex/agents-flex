package com.agentsflex.core.model.exception;

/**
 * 模型账户、项目或组织额度耗尽，不应按临时限速自动重试。
 */
public class ModelQuotaExceededException extends ModelException {

    private final int httpStatus;
    private final String errorCode;
    private final String errorType;

    public ModelQuotaExceededException(String message, int httpStatus, String errorCode,
                                       String errorType) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.errorType = errorType;
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
}
