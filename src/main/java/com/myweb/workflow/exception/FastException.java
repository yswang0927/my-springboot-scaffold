package com.myweb.workflow.exception;

import java.io.PrintStream;
import java.io.PrintWriter;

/**
 * 轻量级异常类，通过禁用堆栈追踪（stack trace）的生成和打印来减少异常抛出时的开销。
 */
abstract class FastException extends RuntimeException {
    public FastException() {
        super();
    }

    public FastException(String message) {
        super(message);
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

    @Override
    public synchronized Throwable initCause(Throwable cause) {
        return this;
    }

}