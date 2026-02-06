package com.myweb.common;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.myweb.exception.FileUploadException;

/**
 * 为前端上传组件(Resumablejs)提供上传服务。
 */
public class ResumableUploader {
    private static final Logger LOG = LoggerFactory.getLogger(ResumableUploader.class);

    // 定义过期时间：5小时
    private static final long EXPIRE_TIME_MILLIS = 5 * 3600 * 1000;
    // 定时清理任务间隔：30分钟
    private static final int CLEANUP_INTERVAL = 30;
    private static final TimeUnit CLEANUP_INTERVAL_TIMEUNIT = TimeUnit.MINUTES;

    private final AtomicBoolean cleanupTaskStarted = new AtomicBoolean(false);
    private ScheduledExecutorService cleanerExecutor;

    private final ConcurrentMap<String, UploadTask> uploadTasksMap = new ConcurrentHashMap<>();

    private final Path uploadDir;
    // 限制单个文件的大小 (字节), <=0 表示不限制
    private final long maxFileSizeBytes;

    public ResumableUploader(File uploadDir, long maxFileSizeBytes) {
        if (uploadDir == null) {
            throw new FileUploadException("uploadDir cannot be null");
        }

        if (!uploadDir.exists() || !uploadDir.isDirectory()) {
            boolean created = uploadDir.mkdirs();
            if (!created && !uploadDir.exists()) {
                throw new FileUploadException("Could not create upload directory");
            }
        }

        this.uploadDir = uploadDir.toPath();
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    /**
     * 保存分片数据。
     * @param chunk 当前分片
     * @param chunkBody 文件分片数据
     * @return true 表示文件上传完成，false 继续上传
     * @exception FileUploadException 如果保存分片数据失败则抛出此异常
     */
    public boolean saveChunk(ResumableChunk chunk, InputStream chunkBody) throws FileUploadException {
        if (chunkBody == null) {
            throw new FileUploadException("分片数据不能为空");
        }

        if (chunk == null) {
            closeBody(chunkBody);
            throw new FileUploadException("无效的分块");
        }

        // 验证参数
        try {
            chunk.validate();
        } catch (FileUploadException e) {
            closeBody(chunkBody);
            throw e;
        }

        final String fileId = chunk.getFileId();
        final String fileName = chunk.getFileName();
        final long fileSize = chunk.getFileSize();
        final long offset = chunk.getChunkStartOffset();

        // 简单的超限检查
        if (this.maxFileSizeBytes > 0 && (fileSize > this.maxFileSizeBytes || offset >= this.maxFileSizeBytes)) {
            throw new FileUploadException("文件大小超限：" + this.maxFileSizeBytes);
        }

        UploadTask uploadTask = this.uploadTasksMap.computeIfAbsent(fileId,
                (id) -> new UploadTask(fileName, chunk.getRelativePath(), chunk.getTotalChunks()));
        uploadTask.touch();

        Path tempFilePath = this.uploadDir.resolve(generateTempFileName(fileId));

        // 先立刻创建完整大小的临时文件占位磁盘，避免后续分块写入时磁盘空间不足
        if (!uploadTask.isFilePreallocated()) {
            synchronized (uploadTask) {
                // 双重检查：确保只有一个线程进行文件创建
                if (!uploadTask.isFilePreallocated()) {
                    if (!Files.exists(tempFilePath)) {
                        try (RandomAccessFile raf = new RandomAccessFile(tempFilePath.toFile(), "rw")) {
                            raf.setLength(fileSize);
                        } catch (IOException e) {
                            // 尝试清理残留空文件
                            try {
                                Files.deleteIfExists(tempFilePath);
                            } catch (IOException ignored) {}
                            throw new FileUploadException(">> ERROR: Failed to allocate disk space for uploading-file: " + fileName);
                        }
                    }
                    uploadTask.markFilePreallocated();
                }
            }
        }

        try (RandomAccessFile raf = new RandomAccessFile(tempFilePath.toFile(), "rw")) {
            raf.seek(offset);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = chunkBody.read(buffer)) != -1) {
                raf.write(buffer, 0, bytesRead);
            }

        } catch (IOException e) {
            throw new FileUploadException(">> ERROR: Failed to saving chunk: " + e.getMessage());
        } finally {
            closeBody(chunkBody);
        }

        // 记录本次成功上传的分块
        uploadTask.addChunk(chunk.getChunkNo());

        // 检查是否全部完成
        if (uploadTask.isAllChunksUploaded()) {
            synchronized (uploadTask) {
                // 双重检查：确保只有一个线程进行文件重命名
                if (Files.exists(tempFilePath)) {
                    String targetFileName = uploadTask.fileName;
                    try {
                        // 1. [安全防御] 防止路径遍历 (重要！例如 ../../etc/passwd)
                        Path targetFilePath = resolveTargetFilePath(targetFileName, uploadTask.relativePath);
                        if (!targetFilePath.startsWith(this.uploadDir)) {
                            throw new FileUploadException("Invalid upload path traversal attempt: " + uploadTask.relativePath);
                        }

                        // 2. 自动创建父目录
                        if (targetFilePath.getParent() != null) {
                            if (!Files.exists(targetFilePath.getParent())) {
                                Files.createDirectories(targetFilePath.getParent());
                            }
                        }

                        Files.move(tempFilePath, targetFilePath, StandardCopyOption.REPLACE_EXISTING);
                        return true;
                    } catch (IOException e) {
                        LOG.error(">> ERROR: Failed to rename uploaded temp-file<{}> to <{}>, caused by: {}",
                                fileId, targetFileName, e.getMessage());
                    }
                }
            }
        }

        return false;
    }

    /**
     * 检查分块是否已上传
     * @param fileId 文件ID
     * @param chunkNo 分块编号
     * @return true: 已上传, false: 未上传
     */
    public boolean isChunkUploaded(String fileId, int chunkNo) {
        return this.uploadTasksMap.containsKey(fileId) && this.uploadTasksMap.get(fileId).isChunkUploaded(chunkNo);
    }

    /**
     * 删除文件（取消上传）
     * @param fileId 文件ID
     */
    public void deleteFile(String fileId) {
        // 先从 Map 移除，防止后续操作
        UploadTask task = this.uploadTasksMap.remove(fileId);
        if (task == null) {
            return;
        }

        try {
            // 先删除临时文件
            Path tempFilePath = this.uploadDir.resolve(generateTempFileName(fileId));
            Files.deleteIfExists(tempFilePath);

            if (StringUtils.hasText(task.fileName)) {
                Path targetFilePath = resolveTargetFilePath(task.fileName, task.relativePath);
                // 安全删除
                if (targetFilePath.startsWith(this.uploadDir)) {
                    boolean deleted = Files.deleteIfExists(targetFilePath);
                    if (deleted) {
                        // 如果删除成功，尝试清理空的父目录
                        deleteEmptyParents(targetFilePath.getParent());
                    }
                }
            }

        } catch (IOException e) {
            LOG.error(">> ERROR: Revert uploaded file<{}> failed: {}", fileId, e.getMessage());
        }
    }

    private Path resolveTargetFilePath(String fileName, String relativePath) {
        // 处理如果上传的是目录
        Path finalFilePath;
        if (StringUtils.hasText(relativePath)) {
            if (relativePath.endsWith(fileName)) {
                finalFilePath = this.uploadDir.resolve(relativePath);
            } else {
                finalFilePath = this.uploadDir.resolve(relativePath).resolve(fileName);
            }
        } else {
            // 没有相对路径，直接存放在根目录
            finalFilePath = this.uploadDir.resolve(fileName);
        }

        // [安全修复] 需要防止路径遍历
        return finalFilePath.normalize();
    }

    /**
     * 递归删除空的父目录，直到到达上传根目录为止
     * @param parentPath 当前需要检查的父目录路径
     */
    private void deleteEmptyParents(Path parentPath) {
        // 1. 安全边界检查：如果 parentPath 为空，或者已经退到了 uploadDir 之外，停止递归
        if (parentPath == null || !parentPath.startsWith(this.uploadDir) || parentPath.equals(this.uploadDir)) {
            return;
        }

        // 2. 尝试列出目录内容
        try (Stream<Path> entries = Files.list(parentPath)) {
            if (!entries.findFirst().isPresent()) {
                Files.delete(parentPath);
                deleteEmptyParents(parentPath.getParent());
            }
        }  catch (Exception e) {
            // 如果删除失败（例如目录非空、权限问题等），直接停止递归，这是正常的
            if (LOG.isDebugEnabled()) {
                LOG.debug("Stopped cleaning empty parents at: {} (Reason: {})", parentPath, e.getMessage());
            }
        }
    }

    private void closeBody(InputStream body) {
        try {
            if (body != null) {
                body.close();
            }
        } catch (Exception ignored) {}
    }

    /**
     * 用于启动定时清理任务，清理过期未完成的上传任务等。
     */
    public void startCleanupTask() {
        if (this.cleanupTaskStarted.compareAndSet(false, true)) {
            LOG.info(">> Start upload cleanup-schedule-task.");
            this.cleanerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "uploader-cleaner");
                t.setDaemon(true);
                return t;
            });
            // 定期检查过期任务
            cleanerExecutor.scheduleAtFixedRate(this::cleanExpiredTasks, CLEANUP_INTERVAL, CLEANUP_INTERVAL, CLEANUP_INTERVAL_TIMEUNIT);
        }
    }

    /**
     * 关闭定时清理任务
     */
    public void shutdownCleanupTask() {
        if (this.cleanupTaskStarted.get()) {
            LOG.info(">> Stop upload cleanup-schedule-task.");
            this.cleanerExecutor.shutdown();
        }
    }

    private String generateTempFileName(String fileId) {
        // 临时文件建议加个后缀或前缀，避免和正式文件混淆
        return "." + fileId + ".temp";
    }

    /**
     * 清理逻辑：
     * 1. 清理过期的 Map 内存 (防止内存泄漏)
     * 2. 清理残留的临时文件 (防止磁盘浪费)
     */
    private void cleanExpiredTasks() {
        long now = System.currentTimeMillis();
        List<String> expiredFileIds = new ArrayList<>();

        this.uploadTasksMap.forEach((id, task) -> {
            if (now - task.lastActiveTime > EXPIRE_TIME_MILLIS) {
                expiredFileIds.add(id);
            }
        });

        for (String fileId : expiredFileIds) {
            UploadTask task = this.uploadTasksMap.remove(fileId);
            // 清理磁盘上过期的临时文件 (如果存在)
            if (task != null) {
                try {
                    Path tempFilePath = this.uploadDir.resolve(generateTempFileName(fileId));
                    boolean res = Files.deleteIfExists(tempFilePath);
                    if (res) {
                        LOG.info(">> 自动删除上传的过期文件: {}", tempFilePath.getFileName().toString());
                    }
                } catch (IOException e) {
                    LOG.warn(">> WARNING: upload-cleanup-task failed to delete expired temp file: {}", fileId);
                }
            }
        }
    }

    // ---------------------------------
    // 内部类：聚合单个上传任务的所有状态
    // ---------------------------------
    private static class UploadTask {
        final String fileName;
        final String relativePath;

        // 最后活跃时间，用于定时清理
        volatile long lastActiveTime = System.currentTimeMillis();
        // 用来判断占位临时文件是否已经创建
        volatile boolean filePreallocated = false;

        private int[] allChunks;

        UploadTask(String fileName, String relativePath, int totalChunks) {
            if (totalChunks < 1) {
                throw new FileUploadException("无效的分块总数: " + totalChunks);
            }
            this.fileName = fileName;
            this.relativePath = relativePath != null ? relativePath : "";
            this.allChunks = new int[totalChunks];
        }

        void touch() {
            this.lastActiveTime = System.currentTimeMillis();
        }

        void markFilePreallocated() {
            this.filePreallocated = true;
        }

        boolean isFilePreallocated() {
            return this.filePreallocated;
        }

        synchronized void addChunk(int chunkNo) {
            this.allChunks[chunkNo - 1] = 1;
        }

        synchronized boolean isChunkUploaded(int chunkNo) {
            if (chunkNo < 1 || chunkNo > this.allChunks.length) {
                return false;
            }
            return this.allChunks[chunkNo - 1] == 1;
        }

        synchronized boolean isAllChunksUploaded() {
            for (int flag : this.allChunks) {
                if (flag != 1) {
                    return false;
                }
            }
            return true;
        }
    }

}
