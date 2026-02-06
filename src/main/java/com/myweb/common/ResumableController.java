package com.myweb.common;

import java.io.File;
import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

}
