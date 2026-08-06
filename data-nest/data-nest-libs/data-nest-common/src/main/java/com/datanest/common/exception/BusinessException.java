package com.datanest.common.exception;

/**
 * Business exception with error code.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private Object data;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail, Object data) {
        super(detail);
        this.errorCode = errorCode;
        this.data = data;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getData() {
        return data;
    }
}
