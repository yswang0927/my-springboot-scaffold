package com.myweb.common;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@IgnoreRestBody
public class ResumableController implements InitializingBean, DisposableBean {
    @Value("${spring.servlet.multipart.location:./uploads}")
    private String uploadDir;

    @Value("${spring.servlet.multipart.max-file-size:100MB}")
    private String maxFileSize;

    private ResumableUploader resumableUploader;

    @Override
    public void afterPropertiesSet() throws Exception {
        this.resumableUploader = new ResumableUploader(new File(this.uploadDir), DataSize.parse(this.maxFileSize).toBytes());
        this.resumableUploader.startCleanupTask(); // 启动定时清理任务
    }

    @Override
    public void destroy() throws Exception {
        if (this.resumableUploader != null) {
            this.resumableUploader.shutdownCleanupTask();
        }
    }

    /**
     * 提供给客户端Resumablejs上传分片(url?fileId=&fileName=&chunkNo=&chunkSize=&...)
     * @param chunk 分片信息
     * @param request 分片请求
     */
    @PostMapping("/api/resumable-upload")
    public ResponseEntity<String> uploading(ResumableChunk chunk, HttpServletRequest request) {
        try (InputStream body = request.getInputStream()) {
            boolean completed = this.resumableUploader.saveChunk(chunk, body);
            if (completed) {
                System.out.println(">>> 文件上传完成："+ chunk.getFileName());
            }
            return ResponseEntity.ok("success");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * 提供给客户端Resumablejs测试分片是否上传成功，用于断点续传
     * @param fileId 文件ID
     * @param chunkNo 分片编号
     */
    @GetMapping("/api/resumable-upload-test")
    public ResponseEntity<Void> isChunkUploaded(@RequestParam("fileId") String fileId, @RequestParam("chunkNo") int chunkNo) {
        boolean chunkUploaded = this.resumableUploader.isChunkUploaded(fileId, chunkNo);
        if (chunkUploaded) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 提供给客户端Resumablejs撤销上传的文件
     * @param fileId 文件ID
     * @param fileName 文件名
     */
    @PostMapping("/api/resumable-upload-revert")
    public ResponseEntity<Void> revertFile(@RequestParam("fileId") String fileId, @RequestParam("fileName") String fileName) {
        this.resumableUploader.revertUploadFile(fileId);
        return ResponseEntity.ok().build();
    }

    /**
     * 单个大文件流式下载接口
     * @param fileName 要下载的文件名（通过参数指定，更灵活）
     * @param request 请求对象（用于处理断点续传）
     * @param response 响应对象
     * @return 流式响应体
     * @throws IOException IO异常
     */
    @GetMapping("/api/stream-download")
    public StreamingResponseBody downloadLargeFile(
            @RequestParam("filename") String fileName,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        // 1. 构建文件路径并校验文件存在性
        Path targetFile = Paths.get(this.uploadDir).resolve(fileName).normalize();
        if (!Files.exists(targetFile) || !targetFile.startsWith(this.uploadDir)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return outputStream -> {
                String errorMsg = "文件不存在: " + fileName;
                outputStream.write(errorMsg.getBytes(StandardCharsets.UTF_8));
            };
        }

        // 2. 获取文件基本信息
        final long fileSize = Files.size(targetFile);
        String mimeType = Files.probeContentType(targetFile);
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }

        // 3. 设置响应头（关键：支持断点续传、指定文件名、防止内存缓存）
        response.setContentType(mimeType);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 处理中文文件名编码
        String encodedFileName = new String(fileName.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFileName + "\"");
        response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileSize));
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes"); // 支持断点续传
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate"); // 禁用缓存
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader(HttpHeaders.EXPIRES, "0");

        // 4. 处理断点续传（可选但推荐，大文件必备）
        long start = 0;
        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String[] rangeParts = rangeHeader.substring(6).split("-");
                start = Long.parseLong(rangeParts[0]);
                if (start > fileSize - 1) {
                    start = 0; // 超出文件大小则从头开始
                } else {
                    // 设置部分内容响应状态
                    response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                    response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + (fileSize - 1) + "/" + fileSize);
                }
            } catch (NumberFormatException e) {
                start = 0; // 解析失败则从头开始
            }
        }

        // 5. 流式写入文件内容（核心：逐块读取，不加载整个文件到内存）
        final long finalStart = start;
        return outputStream -> {
            try (BufferedOutputStream bos = new BufferedOutputStream(outputStream);
                 InputStream inputStream = Files.newInputStream(targetFile)) {

                // 跳过已下载的部分（断点续传）
                if (finalStart > 0) {
                    inputStream.skip(finalStart);
                }

                // 缓冲区大小（可根据服务器性能调整，推荐8KB-64KB）
                byte[] buffer = new byte[8192];
                int bytesRead;
                long bytesWritten = 0;

                // 逐块读取并写入，实现流式传输
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    bos.write(buffer, 0, bytesRead);
                    bos.flush(); // 及时刷出缓冲区，避免堆积
                    bytesWritten += bytesRead;

                    // 可选：监控下载进度（可根据需要添加日志/回调）
                    if (bytesWritten % (1024 * 1024) == 0) {
                        System.out.println("已下载: " + (bytesWritten / 1024 / 1024) + "MB ("+ Math.floor(bytesWritten * 100.0/fileSize) +"%)");
                    }
                }

            } catch (IOException e) {
                // 捕获并包装异常，方便排查
                throw new IOException("大文件下载失败: " + fileName + ", 原因: " + e.getMessage(), e);
            }
        };
    }

}
