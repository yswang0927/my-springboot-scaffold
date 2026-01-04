package com.myweb.common;

import java.io.Serializable;

public class ApiResult<T> implements Serializable {
    private int code = 0;
    private String message = "success";
    private T data;
    private Object errors;

    ApiResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static ApiResult success() {
        return new ApiResult(0, "成功", null);
    }

    public static <T> ApiResult success(T data) {
        return new ApiResult(0, "成功", data);
    }

    public static ApiResult failed(String message) {
        return new ApiResult(500, message, null);
    }

    public static ApiResult failed(int code, String message) {
        return new ApiResult(code, message, null);
    }

    public static ApiResult failed(int code, String message, Object errors) {
        ApiResult result = new ApiResult(code, message, null);
        result.errors = errors;
        return result;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public Object getErrors() {
        return errors;
    }

}
