package com.myweb.license;

/** 许可证体系统一异常（格式非法、验签失败、过期、吊销等）。 */
public class LicenseException extends RuntimeException {

    public LicenseException(String message) {
        super(message);
    }

    public LicenseException(String message, Throwable cause) {
        super(message, cause);
    }
}
