package com.myweb.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.myweb.exception.FileUploadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.BitSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文件上传服务
 */
public class LocalFileUploader {
    private static final Logger LOG = LoggerFactory.getLogger(LocalFileUploader.class);

    private static final ConcurrentHashMap<String, UploadStatus> FILES_UPLOADING_STATUS = new ConcurrentHashMap<>();

    // 定义过期时间：2小时
    private static final long EXPIRE_TIME_MS = 2 * 3600 * 1000;

    // 定时清理任务执行器
    private static final ScheduledExecutorService CLEANER_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "upload-status-cleaner");
        t.setDaemon(true); // 设置为守护线程，不影响 JVM 关闭
        return t;
    });

    static {
        // 每隔 30 分钟检查一次过期任务
        CLEANER_EXECUTOR.scheduleAtFixedRate(LocalFileUploader::cleanExpiredTasks, 30, 30, TimeUnit.MINUTES);
    }

    private static void cleanExpiredTasks() {
        long now = System.currentTimeMillis();
        FILES_UPLOADING_STATUS.entrySet().removeIf(entry -> {
            UploadStatus status = entry.getValue();
            boolean expired = (now - status.getLastUpdateTime()) > EXPIRE_TIME_MS;
            if (expired) {
                // 如果已过期且文件未合并完成，可以考虑清理临时文件
                if (!status.isUploadFinished()) {
                    try {
                        Files.deleteIfExists(Paths.get(status.tmpFilePath));
                    } catch (IOException e) {
                        // 日志记录：清理过期临时文件失败
                    }
                }
                return true; // 从 Map 中移除
            }
            return false;
        });
    }

    public static UploadStatus upload(File targetFile, MultipartFile multipartFile, final long fileTotalSize,
                               final int chunkNo, final int chunkSize, final int totalChunks) throws FileUploadException {
        try (InputStream is = multipartFile.getInputStream()) {
            return upload(targetFile, is, fileTotalSize, chunkNo, chunkSize, totalChunks);
        } catch (IOException e) {
            throw new FileUploadException(String.format("文件 '%s' 上传失败，原因: %s", multipartFile.getName(), e.getMessage()));
        }
    }

    /**
     * 上传文件
     *
     * @param targetFile 存为的目标文件
     * @param fileInputStream 文件输入流
     * @param fileTotalSize 文件总大小
     * @param chunkNo 当前分块编号
     * @param chunkSize 当前分块大小
     * @param totalChunks 总分块数
     * @exception FileUploadException 如果文件写入失败，则抛出此异常
     */
    public static UploadStatus upload(File targetFile, InputStream fileInputStream, final long fileTotalSize,
                       final int chunkNo, final int chunkSize, final int totalChunks) throws FileUploadException {

        if (targetFile == null) {
            throw new FileUploadException("目标文件不能为空");
        }
        if (fileInputStream == null) {
            throw new FileUploadException("文件输入流不能为空");
        }
        if (fileTotalSize < 0) {
            throw new FileUploadException("文件总大小不能为负数");
        }
        if (chunkNo <= 0 || chunkNo > totalChunks) {
            throw new FileUploadException(String.format("分块编号非法，当前分块：%d，总分块数：%d", chunkNo, totalChunks));
        }
        if (chunkSize <= 0) {
            throw new FileUploadException("分块大小不能小于等于0");
        }
        if (totalChunks < 1) {
            throw new FileUploadException("总分块数不能小于1");
        }

        File parentDir = targetFile.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw new FileUploadException(String.format("创建目录 '%s' 失败", parentDir.getAbsolutePath()));
        }

        final String filePath = targetFile.getAbsolutePath();
        final String fileName = targetFile.getName();

        UploadStatus uploadStatus = FILES_UPLOADING_STATUS.computeIfAbsent(filePath,
                key -> new UploadStatus(targetFile, fileTotalSize, totalChunks));

        // 检查该块是否已经上传过，实现断点续传逻辑
        if (uploadStatus.isChunkUploaded(chunkNo)) {
            return uploadStatus;
        }

        // 如果只有一块，直接写目标文件；否则写临时文件
        Path wFilePath = Paths.get(uploadStatus.totalChunks > 1 ? uploadStatus.tmpFilePath : uploadStatus.filePath);
        // 使用 FileChannel 可以支持并发写入
        try (FileChannel channel = FileChannel.open(wFilePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            long writePos = (long) (chunkNo - 1) * chunkSize;
            byte[] readBytes = new byte[8192];
            long writtenInThisChunk = 0;
            int read = -1;
            while ((read = fileInputStream.read(readBytes)) != -1) {
                ByteBuffer out = ByteBuffer.wrap(readBytes, 0, read);
                while (out.hasRemaining()) {
                    int written = channel.write(out, writePos + writtenInThisChunk);
                    writtenInThisChunk += written;
                }
                out.clear();
            }

            uploadStatus.uploadedSize.addAndGet(writtenInThisChunk);
            uploadStatus.markChunkUploaded(chunkNo);

            // 上传完成重命名文件
            if (uploadStatus.isUploadComplete()) {
                // 同一实例加锁，确保只有一个线程进行文件重命名
                synchronized (uploadStatus) {
                    if (uploadStatus.isFinished.compareAndSet(false, true)
                            && Files.exists(wFilePath) && totalChunks > 1) {
                        Files.move(wFilePath, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        FILES_UPLOADING_STATUS.remove(filePath);
                    }
                }
            }

        } catch (Exception e) {
            LOG.error("文件 '{}' 上传失败: ", fileName, e);
            throw new FileUploadException(String.format("文件 '%s' 上传失败，原因: %s", fileName, e.getMessage()));
        }

        return uploadStatus;
    }

    public static class UploadStatus {
        final String fileId;
        @JsonIgnore
        final String filePath;
        @JsonIgnore
        final String tmpFilePath;
        final long fileTotalSize;
        final int totalChunks;
        AtomicLong uploadedSize;
        @JsonIgnore
        BitSet uploadedChunks;
        AtomicBoolean isFinished;
        @JsonIgnore
        long lastUpdateTime;

        UploadStatus(File targetFile, long fileTotalSize, int totalChunks) {
            this.fileId = UUID.randomUUID().toString();
            this.filePath = targetFile.getAbsolutePath();
            this.tmpFilePath = new File(targetFile.getParentFile(), UUID.randomUUID().toString() + ".tmp").getAbsolutePath();
            this.fileTotalSize = fileTotalSize;
            this.totalChunks = totalChunks;
            this.uploadedSize = new AtomicLong(0);
            this.uploadedChunks = new BitSet(totalChunks);
            this.isFinished = new AtomicBoolean(false);
            this.lastUpdateTime = System.currentTimeMillis();
        }

        /**
         * 标记分块完成（同步 BitSet 操作）
         */
        synchronized void markChunkUploaded(int chunkNo) {
            if (chunkNo >= 1 && chunkNo <= totalChunks) {
                this.uploadedChunks.set(chunkNo - 1);
                this.lastUpdateTime = System.currentTimeMillis();
            }
        }

        /**
         * 检查分块是否已上传（同步 BitSet 操作）
         */
        synchronized boolean isChunkUploaded(int chunkNo) {
            if (chunkNo >= 1 && chunkNo <= totalChunks) {
                return uploadedChunks.get(chunkNo - 1);
            }
            return false;
        }

        /**
         * 判断是否全部上传完成
         */
        synchronized boolean isUploadComplete() {
            return this.uploadedChunks.cardinality() == this.totalChunks
                    && this.uploadedSize.get() == this.fileTotalSize;
        }

        public String getFileId() {
            return fileId;
        }

        public String getFilePath() {
            return filePath;
        }

        public long getFileTotalSize() {
            return fileTotalSize;
        }

        public int getTotalChunks() {
            return totalChunks;
        }

        public long getUploadedSize() {
            return uploadedSize.get();
        }

        public boolean isUploadFinished() {
            return isFinished.get();
        }

        public long getLastUpdateTime() {
            return lastUpdateTime;
        }
    }

}
