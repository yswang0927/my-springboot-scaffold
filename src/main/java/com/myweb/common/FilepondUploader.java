package com.myweb.common;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import com.myweb.exception.FileUploadException;

/**
 * 为前端上传组件(https://github.com/pqina/filepond)提供上传服务
 */
public class FilepondUploader {
    private static final Logger LOG = LoggerFactory.getLogger(FilepondUploader.class);

    // 定义过期时间：5小时
    private static final long EXPIRE_TIME_MILLIS = 5 * 3600 * 1000;
    // 定时清理任务间隔：30分钟
    private static final int CLEANUP_INTERVAL = 30;
    private static final TimeUnit CLEANUP_INTERVAL_TIMEUNIT = TimeUnit.MINUTES;

    private final AtomicBoolean cleanupTaskStarted = new AtomicBoolean(false);

    private final ConcurrentMap<String, UploadTask> uploadTasksMap = new ConcurrentHashMap<>();

    private final Path uploadDir;
    // 限制单个文件的大小 (字节), <=0 表示不限制
    private final long maxFileSizeBytes;

    public FilepondUploader(File uploadDir, long maxFileSizeBytes) {
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
     * 初始化上传，生成唯一ID。
     * @param fileName 文件名
     * @param fileSize 文件大小，用于磁盘占位
     */
    public String startUpload(String fileName, long fileSize) throws FileUploadException {
        if (this.maxFileSizeBytes > 0 && fileSize > this.maxFileSizeBytes) {
            throw new FileUploadException("File size exceeds limit: " + this.maxFileSizeBytes);
        }

        String fileId = UUID.randomUUID().toString();
        Path tempFilePath = this.uploadDir.resolve(generateTempFileName(fileId));
        // 预分配磁盘空间 (占位)，便于并发上传写入性能
        try (RandomAccessFile raf = new RandomAccessFile(tempFilePath.toFile(), "rw")) {
            raf.setLength(fileSize);
        } catch (IOException e) {
            throw new FileUploadException(">> ERROR: Failed to allocate disk space for uploading-file: " + fileName);
        }

        this.uploadTasksMap.put(fileId, new UploadTask(fileName, fileSize));
        return fileId;
    }

    /**
     * 保存分片数据
     * @param fileId 文件ID
     * @param offset 偏移量
     * @param body 分片二进制数据
     * @param totalLength 文件总大小
     */
    public void saveChunk(String fileId, long offset, InputStream body, long totalLength) throws FileUploadException {
        UploadTask uploadTask = this.uploadTasksMap.get(fileId);
        if (uploadTask == null) {
            throw new FileUploadException("Upload session expired or invalid ID: "+ fileId);
        }

        uploadTask.touch();

        // 简单的超限检查
        if (this.maxFileSizeBytes > 0 && ((offset + 1) > this.maxFileSizeBytes)) {
            throw new FileUploadException("File size exceeds limit: " + this.maxFileSizeBytes);
        }

        Path tempFilePath = this.uploadDir.resolve(generateTempFileName(fileId));
        long bytesWrittenInThisChunk = 0;

        try (RandomAccessFile raf = new RandomAccessFile(tempFilePath.toFile(), "rw")) {
            raf.seek(offset);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = body.read(buffer)) != -1) {
                raf.write(buffer, 0, bytesRead);
                bytesWrittenInThisChunk += bytesRead;
            }

        } catch (IOException e) {
            throw new FileUploadException(">> ERROR: Failed to saving chunk: " + e.getMessage());
        } finally {
            if (body != null) {
                try {
                    body.close();
                } catch (Exception ignored) {}
            }
        }

        // 记录本次成功上传的区间 [start, end)
        final long contiguousLength = uploadTask.addChunkRange(offset, offset + bytesWrittenInThisChunk);
        // 检查是否全部完成，判断标准：从0开始的连续长度 == 文件总长度
        if (contiguousLength >= Math.max(totalLength, uploadTask.totalLength)) {
            synchronized (uploadTask) {
                // 双重检查：确保只有一个线程进行文件重命名
                if (Files.exists(tempFilePath)) {
                    String fileName = uploadTask.fileName;
                    if (!StringUtils.hasText(fileName)) {
                        fileName = fileId;
                    }
                    // 安全性处理：只保留文件名，防止路径遍历攻击 (../../)
                    String safeFileName = Paths.get(fileName).getFileName().toString();
                    try {
                        Files.move(tempFilePath, this.uploadDir.resolve(safeFileName), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        LOG.error(">> ERROR: Failed to rename uploaded temp-file<{}> to <{}>, caused by: {}",
                                tempFilePath.getFileName().toString(), fileName, e.getMessage());
                    }
                }
            }
        }

    }

    /**
     * 获取下一个分片上传的偏移量(用于断点续传)
     */
    public long getNextUploadOffset(String fileId) {
        UploadTask task = this.uploadTasksMap.get(fileId);
        return (task != null) ? task.getContiguousLength() : 0;
    }

    /**
     * 删除文件（取消上传）
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
                // [安全修复] 需要防止路径遍历
                String safeFileName = Paths.get(task.fileName).getFileName().toString();
                Path filePath = this.uploadDir.resolve(safeFileName);
                Files.deleteIfExists(filePath);
            }

        } catch (IOException e) {
            LOG.error(">> ERROR: Revert uploaded file<{}> failed: {}", fileId, e.getMessage());
        }
    }

    /**
     * 用于启动定时清理任务，清理过期未完成的上传任务等。
     */
    public void startCleanupTask() {
        if (this.cleanupTaskStarted.compareAndSet(false, true)) {
            LOG.info(">> Start upload cleanup-schedule-task.");
            ScheduledExecutorService cleanerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "uploader-cleaner");
                t.setDaemon(true);
                return t;
            });
            // 定期检查过期任务
            cleanerExecutor.scheduleAtFixedRate(this::cleanExpiredTasks, CLEANUP_INTERVAL, CLEANUP_INTERVAL, CLEANUP_INTERVAL_TIMEUNIT);
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

        if (LOG.isDebugEnabled()) {
            LOG.debug(">> 扫描到过期临时文件：{}", expiredFileIds);
        }

        for (String fileId : expiredFileIds) {
            UploadTask task = this.uploadTasksMap.remove(fileId);
            // 清理磁盘上过期的临时文件 (如果存在)
            if (task != null) {
                try {
                    Path tempFilePath = this.uploadDir.resolve(generateTempFileName(fileId));
                    Files.deleteIfExists(tempFilePath);
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
        final long totalLength;
        // 最后活跃时间，用于定时清理
        volatile long lastActiveTime = System.currentTimeMillis();
        // 用于维护已上传的区间列表，例如: [[0, 100], [200, 300]]
        private final List<Range> uploadedChunkRanges = new ArrayList<>();

        UploadTask(String fileName, long totalLength) {
            this.fileName = fileName;
            this.totalLength = totalLength;
        }

        void touch() {
            this.lastActiveTime = System.currentTimeMillis();
        }

        /**
         * 添加一个新的上传区间，并自动合并重叠/相邻的区间
         * @return 返回合并后，从 0 开始的连续长度
         */
        synchronized long addChunkRange(long start, long end) {
            // 1. 添加新区间
            this.uploadedChunkRanges.add(new Range(start, end));
            // 2. 排序 (按起始位置)
            this.uploadedChunkRanges.sort((r1, r2) -> Long.compare(r1.start, r2.start));

            // 3. 合并逻辑
            List<Range> merged = new ArrayList<>();
            Range current = this.uploadedChunkRanges.get(0);

            for (int i = 1; i < this.uploadedChunkRanges.size(); i++) {
                Range next = this.uploadedChunkRanges.get(i);
                if (current.end >= next.start) {
                    // 有重叠或相邻，合并 (取最大的结束位置)
                    current.end = Math.max(current.end, next.end);
                } else {
                    // 无重叠，存入 current，开启新的区间
                    merged.add(current);
                    current = next;
                }
            }
            merged.add(current); // 添加最后一个

            // 更新
            this.uploadedChunkRanges.clear();
            this.uploadedChunkRanges.addAll(merged);

            // 4. 计算从 0 开始的连续长度
            return getContiguousLength();
        }

        /**
         * 获取从 offset 0 开始的连续长度
         */
        synchronized long getContiguousLength() {
            if (this.uploadedChunkRanges.isEmpty()) {
                return 0;
            }
            // 检查第一个区间是否从 0 开始
            Range first = this.uploadedChunkRanges.get(0);
            if (first.start == 0) {
                return first.end;
            }
            return 0; // 第一个区间不是从0开始的（比如先传了后半段），那有效Offset还是0
        }
    }

    /**
     * 上传块区间
     */
    private static class Range {
        long start;
        long end;

        Range(long start, long end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public String toString() {
            return String.format("[%d, %d)", start, end);
        }
    }

}
