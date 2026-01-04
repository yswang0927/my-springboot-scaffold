package com.myweb.exception;

/**
 * 未认证异常(用户没有提供身份凭证（如未登录），或者提供的凭证无效/过期)
 * <p>后续操作：引导用户去登录页面，或者要求用户提供有效的认证信息。</p>
 */
public class UnAuthenticatedException extends BadRequestException {
    public static final int ERROR_CODE = 401;

    public UnAuthenticatedException() {
        this("需要身份认证");
    }

    public UnAuthenticatedException(String message) {
        this(ERROR_CODE, message);
    }

    public UnAuthenticatedException(int errorCode, String message) {
        super(errorCode, message);
    }

}
