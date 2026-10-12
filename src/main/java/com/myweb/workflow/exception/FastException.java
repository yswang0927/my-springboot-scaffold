package com.myweb.workflow.exception;

import java.io.PrintStream;
import java.io.PrintWriter;

/**
 * 轻量级异常：禁用堆栈填充以降低控制流异常开销。
 */
abstract class FastException extends RuntimeException {
    public FastException() {
        super();
    }

    public FastException(String message) {
        super(message);
    }

    public FastException(String message, Throwable cause) {
        super(message, cause);
    }

    public FastException(Throwable cause) {
        super(cause);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

    @Override
    public StackTraceElement[] getStackTrace() {
        return new StackTraceElement[0];
    }

    @Override
    public void setStackTrace(StackTraceElement[] stackTrace) {
        // ignored
    }

    @Override
    public void printStackTrace() {
        // ignored
    }

    @Override
    public void printStackTrace(PrintStream s) {
        // ignored
    }

    @Override
    public void printStackTrace(PrintWriter s) {
        // ignored
    }
}
