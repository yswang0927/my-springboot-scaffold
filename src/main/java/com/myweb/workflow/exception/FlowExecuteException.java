package com.myweb.workflow.exception;

public class FlowExecuteException extends RuntimeException {
    public FlowExecuteException() {
        super();
    }

    public FlowExecuteException(String message) {
        super(message);
    }

    public FlowExecuteException(String message, Throwable cause) {
        super(message, cause);
    }

    public FlowExecuteException(Throwable cause) {
        super(cause);
    }
}
