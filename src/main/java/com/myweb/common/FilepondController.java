package com.myweb.common;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 为前端 Filepond 组件提供提供上传服务。
 * Filepond 分块上传的流程如下：
 * 1. FilePond 将发送一个 POST 请求（不带文件）来启动分块传输，并期望在响应正文中收到唯一的传输 ID，它会将 Upload-Length 标头添加到此请求中。
 * 2. FilePond 会发送 PATCH 请求将一个数据块推送到服务器。每个此类请求都附带 Content-Type 、 Upload-Offset 、 Upload-Name 和 Upload-Length 标头。
 * 3. FilePond 将发送 HEAD 请求以确定哪些数据块已经上传，并期望在 Upload-Offset 响应头中获得下一个预期数据块的文件偏移量。
 */
@RestController
public class FilepondController {

    @PostMapping("/api/filepond-upload")
    public String startUpload(@RequestHeader(value = "Upload-Length") long fileSize) {
        return UUID.randomUUID().toString();
    }

    @PatchMapping("/api/filepond-upload")
    public void uploading(@RequestPart("file") MultipartFile file,
                       @RequestHeader(value = "Upload-Name") String fileName, // 正在传输的文件的名称
                       @RequestHeader(value = "Upload-Length") long fileSize, // 正在传输的文件总大小
                       @RequestHeader(value = "Upload-Offset") long uploadOffset, // 正在传输的数据块的偏移量
                       @RequestHeader(value = "Upload-Id") String fileId) {
        System.out.println("正在上传的文件：" + fileName);
    }


}
