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

    private static final int BUFFER_SIZE = 8192;
    private static final String TEMP_FILE_PREFIX = ".rsm-";
    private static final String TEMP_FILE_SUFFIX = ".tmp";

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
            closeQuietly(chunkBody);
            throw new FileUploadException("无效的分块参数");
        }

        // 验证参数
        try {
            chunk.validate();
        } catch (FileUploadException e) {
            closeQuietly(chunkBody);
            throw e;
        }

        final String fileId = chunk.getFileId();
        final String fileName = chunk.getFileName();
        final long fileSize = chunk.getFileSize();
        final int chunkNo = chunk.getChunkNo();
        final long offset = chunk.getChunkStartOffset();

        // 简单的超限检查
        if (this.maxFileSizeBytes > 0 && (fileSize > this.maxFileSizeBytes || offset >= this.maxFileSizeBytes)) {
            throw new FileUploadException("文件大小超限：" + this.maxFileSizeBytes);
        }

        UploadTask uploadTask = this.uploadTasksMap.computeIfAbsent(fileId, (id) -> new UploadTask(fileName, chunk.getRelativePath(), chunk.getTotalChunks()));
        uploadTask.touch(); // 更新最后活跃时间，避免被定时清理

        // 生成临时文件路径（上传根目录下的隐藏临时文件）
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

                            throw new FileUploadException(String.format("为文件 %s 预分配磁盘空间失败：%s", fileName, e.getMessage()));
                        }
                    }
                    // 标记临时文件已分配磁盘空间
                    uploadTask.markFilePreallocated();
                }
            }
        }

        try (RandomAccessFile raf = new RandomAccessFile(tempFilePath.toFile(), "rw")) {
            raf.seek(offset);

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = chunkBody.read(buffer)) != -1) {
                raf.write(buffer, 0, bytesRead);
            }

        } catch (IOException e) {
            throw new FileUploadException(String.format("保存文件 %s 分块 %d 失败：%s", fileId, chunkNo, e.getMessage()));
        } finally {
            closeQuietly(chunkBody);
        }

        // 标记分块为已上传
        uploadTask.addChunk(chunk.getChunkNo());

        // 检查是否全部完成
        if (uploadTask.isAllChunksUploaded()) {
            synchronized (uploadTask) {
                // 双重检查：确保只有一个线程进行文件重命名
                if (Files.exists(tempFilePath) && !uploadTask.isCompleted()) {
                    String targetFileName = uploadTask.fileName;
                    try {
                        // 1. [安全防御] 防止路径遍历 (重要！例如 ../../etc/passwd)
                        Path targetFilePath = resolveTargetFilePath(targetFileName, uploadTask.relativePath);
                        // 2. 自动创建父目录
                        if (targetFilePath.getParent() != null && !Files.exists(targetFilePath.getParent())) {
                            Files.createDirectories(targetFilePath.getParent());
                        }

                        // 3. 移动临时文件为正式文件，替换已存在的文件
                        Files.move(tempFilePath, targetFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        uploadTask.markCompleted(); // 标记任务完成
                        // 注意：这里不要立刻移除缓存任务，因为客户端可能会撤销上传，需要提供给后面可能的删除文件使用
                        // 缓存的清理由 cleanup 定时任务处理
                        //this.uploadTasksMap.remove(fileId);
                        return true;
                    } catch (IOException e) {
                        throw new FileUploadException("文件上传完成，重命名失败：" + e.getMessage());
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
    public void revertUploadFile(String fileId) {
        // 先从 Map 移除，防止后续操作
        UploadTask task = this.uploadTasksMap.remove(fileId);
        if (task == null) {
            return;
        }

        try {
            // 先删除临时文件
            Path tempFilePath = this.uploadDir.resolve(generateTempFileName(fileId));
            Files.deleteIfExists(tempFilePath);

            if (hasText(task.fileName)) {
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
            LOG.error(">> ERROR: 删除上传的文件 <{}> 失败: {}", task.fileName, e.getMessage());
        }
    }

    private Path resolveTargetFilePath(String fileName, String relativePath) {
        // 处理如果上传的是目录
        Path finalFilePath;
        if (hasText(relativePath)) {
            if (relativePath.endsWith(fileName)) {
                finalFilePath = this.uploadDir.resolve(relativePath);
            } else {
                finalFilePath = this.uploadDir.resolve(relativePath).resolve(fileName);
            }
        } else {
            // 没有相对路径，直接存放在根目录
            finalFilePath = this.uploadDir.resolve(fileName);
        }

        // [安全防御] 防止路径遍历 (重要！例如 ../../etc/passwd)
        Path normalizedPath = finalFilePath.normalize();
        if (!normalizedPath.startsWith(this.uploadDir)) {
            throw new FileUploadException("非法的文件上传路径，禁止目录遍历：" + relativePath);
        }
        return normalizedPath;
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
            LOG.error(">> ERROR: 删除上传的空目录 <{}> 失败: {}", parentPath, e.getMessage());
        }
    }

    private void closeQuietly(InputStream body) {
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
            this.cleanerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "uploader-cleaner");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            // 定期检查过期任务
            cleanerExecutor.scheduleAtFixedRate(this::cleanExpiredTasks, CLEANUP_INTERVAL, CLEANUP_INTERVAL, CLEANUP_INTERVAL_TIMEUNIT);
            LOG.info(">> 分块上传定时清理任务已启动，间隔{}分钟，过期时间{}小时", CLEANUP_INTERVAL, EXPIRE_TIME_MILLIS / 3600000);
        }
    }

    /**
     * 关闭定时清理任务
     */
    public void shutdownCleanupTask() {
        if (this.cleanupTaskStarted.compareAndSet(true, false)) {
            LOG.info(">> 开始关闭分块上传定时清理任务...");
            this.cleanerExecutor.shutdown();
            try {
                if (!this.cleanerExecutor.awaitTermination(1, TimeUnit.MINUTES)) {
                    this.cleanerExecutor.shutdownNow(); // 强制关闭
                }
            } catch (InterruptedException e) {
                this.cleanerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOG.info(">> 分块上传定时清理任务已关闭");
        }
    }

    private String generateTempFileName(String fileId) {
        // 临时文件名
        return TEMP_FILE_PREFIX + fileId.replace("/", "") + TEMP_FILE_SUFFIX;
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
                    LOG.warn(">> WARNING: 自动删除上传的过期文件 <{}> 失败: {}", fileId, e.getMessage());
                }
            }
        }
    }

    public static boolean hasText(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        for (int i = 0,len = str.length(); i < len; i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------
    // 内部类：聚合单个上传任务的所有状态
    // ---------------------------------
    private static class UploadTask {
        final String fileName;
        final String relativePath;

        // 最后活跃时间，用于定时清理
        volatile long lastActiveTime = System.currentTimeMillis();
        // 文件是否已预分配磁盘空间
        volatile boolean filePreallocated;
        // 任务是否已完成（所有分块上传并生成正式文件）
        volatile boolean completed;

        private int[] allChunks;

        UploadTask(String fileName, String relativePath, int totalChunks) {
            if (totalChunks < 1) {
                throw new FileUploadException("无效的分块总数: " + totalChunks);
            }
            this.fileName = ResumableUploader.hasText(fileName) ? fileName : "unknown-file";
            this.relativePath = ResumableUploader.hasText(relativePath) ? relativePath : "";
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

        /**
         * 标记任务已完成
         */
        void markCompleted() {
            this.completed = true;
        }

        boolean isCompleted() {
            return this.completed;
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
