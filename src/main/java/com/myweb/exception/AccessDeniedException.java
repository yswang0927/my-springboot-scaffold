package com.myweb.exception;

/**
 * 访问被拒绝(未授权)异常(用户身份已知且合法，但其角色或权限不足以访问该资源)。
 * <p>后续操作：提示用户权限不足</p>
 */
public class AccessDeniedException extends BadRequestException {
    public static final int ERROR_CODE = 403;

    public AccessDeniedException() {
        this("拒绝访问");
    }

    public AccessDeniedException(String message) {
        this(ERROR_CODE, message);
    }

    public AccessDeniedException(int errorCode, String message) {
        super(errorCode, message);
    }
}
