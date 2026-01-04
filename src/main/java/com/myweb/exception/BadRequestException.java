package com.myweb.exception;

/**
 * 客户端无效请求异常
 */
public class BadRequestException extends RuntimeException {
    private int errorCode = 400;

    public BadRequestException() {
        this("无效请求");
    }

    public BadRequestException(String message) {
        this(400, message);
    }

    public BadRequestException(int errorCode, String message) {
        super(message, null, false, false);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }

    @Override
    public void setStackTrace(StackTraceElement[] stackTrace) {
        // ignore
    }

    @Override
    public StackTraceElement[] getStackTrace() {
        return new StackTraceElement[0];
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return null;
    }

}
