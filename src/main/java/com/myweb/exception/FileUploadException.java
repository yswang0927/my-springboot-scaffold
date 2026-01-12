package com.myweb.exception;

/***
 * 文件上传异常
 */
public class FileUploadException extends BadRequestException {
    public FileUploadException() {
        super("文件上传异常");
    }

    public FileUploadException(String message) {
        super(message);
    }
}
