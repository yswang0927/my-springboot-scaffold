package com.myweb.common;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;

import com.myweb.exception.FileUploadException;

/**
 * 为前端 Filepond 组件提供分块上传服务。
 * <p>请求中使用的自定义标头：</p>
 * <pre>
 * Upload-Length - 正在传输的文件总大小
 * Upload-Name - 正在传输的文件的名称
 * Upload-Offset - 正在传输的数据块的偏移量
 * Content-Type - 请求的内容类型 'application/offset+octet-stream'
 * </pre>
 *
 * Filepond 分块上传的流程如下：
 * <pre>
 * 1. FilePond 将发送一个 POST 请求（不带文件）来启动分块传输，并期望在响应正文中收到唯一的传输 ID，它会将 Upload-Length 标头添加到此请求中。
 * 2. FilePond 会发送 PATCH 请求将一个数据块推送到服务器。每个此类请求都附带 Content-Type, Upload-Offset, Upload-Name 和 Upload-Length 标头。
 * 3. FilePond 将发送 HEAD 请求以确定哪些数据块已经上传，并期望在 Upload-Offset 响应头中获得下一个预期数据块的文件偏移量。
 * </pre>
 */
@RestController
@RequestMapping("/api/filepond-upload")
@IgnoreRestBody
public class FilepondController implements InitializingBean {

    @Value("${spring.servlet.multipart.location:./uploads}")
    private String uploadDir;

    @Value("${spring.servlet.multipart.max-file-size:100MB}")
    private String maxFileSize;

    private FilepondUploader filepondUploader;

    public FilepondController() {
    }

    /**
     * 1. 启动文件上传，先生成一个唯一ID。
     * 唯一 ID 用于回滚上传或恢复之前的上传等。
     */
    @IgnoreRestBody
    @PostMapping("/process")
    public ResponseEntity<String> startUpload(HttpServletRequest request) {
        String uploadName = request.getHeader("Upload-Name");
        String uploadLength = request.getHeader("Upload-Length");
        // [新增] 获取相对路径 Header
        String uploadPath = request.getHeader("Upload-Path");

        String fileName = "unknown_file_" + System.currentTimeMillis();
        String relativePath = ""; // 默认为空，即存放在根目录

        if (StringUtils.hasText(uploadName)) {
            try {
                // 前端使用 encodeURIComponent() 编码了文件名防止中文乱码
                fileName = URLDecoder.decode(uploadName, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                // 如果解码失败，回退到原始值或默认值
                fileName = uploadName;
            }
        }

        if (StringUtils.hasText(uploadPath)) {
            try {
                relativePath = URLDecoder.decode(uploadPath, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                relativePath = uploadPath;
            }
        }

        long fileSize = 0;
        try {
            fileSize = Long.parseLong(uploadLength);
        } catch (NumberFormatException e) {
            // ignore
        }

        try {
            return ResponseEntity.ok(this.filepondUploader.startUpload(fileName, fileSize, relativePath));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Override
    public void afterPropertiesSet() {
        this.filepondUploader = new FilepondUploader(new File(this.uploadDir), DataSize.parse(this.maxFileSize).toBytes());
        this.filepondUploader.startCleanupTask(); // 启动清理任务
    }

    /**
     * 2. 使用 PATCH 请求发送第一个数据块，并在 URL 中(?patch=file_Id)添加唯一ID;
     * 每个 PATCH 请求都附带 Upload-Offset, Upload-Length 和 Upload-Name 三个头部。
     * <pre>
     * - Upload-Offset 头部包含数据块的字节偏移量
     * - Upload-Length 头部包含文件总大小
     * - Upload-Name 头部包含文件名
     * </pre>
     * FilePond 会分块发送文件，直到所有数据块都成功上传为止。
     */
    @IgnoreRestBody
    @PatchMapping("/process")
    public ResponseEntity<Void> uploading(HttpServletRequest request) {
        String fileId = request.getParameter("patch");
        String uploadOffset = request.getHeader("Upload-Offset");
        String uploadLength = request.getHeader("Upload-Length");

        if (!StringUtils.hasText(fileId)) {
            return ResponseEntity.badRequest().build();
        }

        // 注意：分块数据包是通过 application/offset+octet-stream 发送的二进制请求体，
        // 无法使用 MultipartFile 获取数据，必须使用 request.getInputStream() 来获取数据
        try (InputStream chunkBody = request.getInputStream()) {
            long uploadOffsetLong = Long.parseLong(uploadOffset);
            long fileSize = Long.parseLong(uploadLength);
            if (fileSize < 0) {
                throw new IllegalArgumentException("无效的 Upload-Length 值");
            }
            if (uploadOffsetLong < 0 || uploadOffsetLong >= fileSize) {
                throw new IllegalArgumentException("无效的 Upload-Offset 参数值");
            }

            this.filepondUploader.saveChunk(fileId, uploadOffsetLong, chunkBody, fileSize);

            return ResponseEntity.ok(null);
        }
        catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        }
        catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 3. 断点续传检查 (HEAD /process?patch={id})
     * 将发送 HEAD 请求(并在 URL 中附带传输ID)以确定哪些数据块已经上传，
     * 并期望在 Upload-Offset 响应头中获得下一个预期数据块的文件偏移量。
     * 服务器响应 Upload-Offset 将其设置为下一个预期的数据块偏移量（以字节为单位）。
     */
    @IgnoreRestBody
    @RequestMapping(path="/process", method=RequestMethod.HEAD)
    public ResponseEntity<Void> checkUpload(HttpServletRequest request) {
        String fileId = request.getParameter("patch");
        if (!StringUtils.hasText(fileId)) {
            // 如果没有 ID，可能不是断点续传请求，返回 400 或 404
            return ResponseEntity.badRequest().build();
        }

        long nextOffset = this.filepondUploader.getNextUploadOffset(fileId);
        // 返回当前服务器已接收的字节数
        return ResponseEntity.ok()
                .header("Upload-Offset", String.valueOf(nextOffset))
                .build();
    }

    /**
     * 4. 取消/回滚 (DELETE)
     * 点击撤销按钮后， FilePond 会发送请求体(payload)为 12345 的 DELETE 请求。
     * 服务器删除与提供的 ID 匹配的临时文件，并返回空响应。
     */
    @IgnoreRestBody
    @DeleteMapping("/revert")
    public ResponseEntity<Void> revert(HttpServletRequest request) throws IOException {
        // FilePond 发送 DELETE 请求时，body 里是 fileId
        try (InputStream body = request.getInputStream()) {
            String fileId = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            // FilePond 有时会发送带引号的字符串 (例如 "uuid")，稍微清洗一下
            fileId = fileId.replace("\"", "");
            this.filepondUploader.deleteFile(fileId);
            return ResponseEntity.ok().build();
        }
    }

    /**
     * fetch 用于加载位于远程服务器上的文件。
     * 当用户发送远程链接时，FilePond 会请求服务器下载该文件（否则 CORS 可能会阻止下载）。
     * 在这种情况下，服务器充当代理。
     */
    @IgnoreRestBody
    @RequestMapping(path = "/fetch", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void fetch(HttpServletRequest request) throws IOException {
        String fetchUrl = request.getParameter("fetch");
        // todo
    }

    /**
     * 使用 restore 端点来恢复临时服务器文件。
     * 这在用户关闭浏览器窗口但尚未完成表单填写的情况下非常有用。
     * 可以使用 files 属性设置临时文件。
     */
    @IgnoreRestBody
    @RequestMapping(path = "/restore", method = RequestMethod.GET)
    public void restore(HttpServletRequest request) throws IOException {
        String fetchUrl = request.getParameter("restore");
        // todo
    }

    /**
     * 用于恢复已上传的服务器文件。
     * 这些文件可能位于数据库中，也可能位于服务器文件系统中的其他位置。
     * 无论哪种情况，它们都可能无法直接从 Web 访问。
     */
    @IgnoreRestBody
    @RequestMapping(path = "/load", method = RequestMethod.GET)
    public void load(HttpServletRequest request) throws IOException {
        String fetchUrl = request.getParameter("load");
        // todo
    }

}
