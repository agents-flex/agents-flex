package com.agentsflex.core.model.exception;

/**
 * 模型服务暂时过载，通常可以采用退避策略重试。
 */
public class ModelOverloadedException extends ModelException {

    private final int httpStatus;
    private final String errorCode;
    private final String errorType;

    public ModelOverloadedException(String message, int httpStatus, String errorCode,
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
