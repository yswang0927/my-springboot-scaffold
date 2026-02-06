package com.myweb.common;

import com.myweb.exception.FileUploadException;

public class ResumableChunk {
    private String fileId;
    private String fileName;
    // 分块编号（从1开始）
    private Integer chunkNo;
    // 预设分块大小（字节）
    private Long chunkSize;
    // 当前分块实际大小（字节）
    private Long currentChunkSize;
    private Integer totalChunks;
    // 文件总大小（字节）
    private Long fileSize;
    private String fileMD5;
    private String relativePath;

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Integer getChunkNo() {
        return chunkNo;
    }

    public void setChunkNo(Integer chunkNo) {
        this.chunkNo = chunkNo;
    }

    public Long getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(Long chunkSize) {
        this.chunkSize = chunkSize;
    }

    public Long getCurrentChunkSize() {
        return currentChunkSize;
    }

    public void setCurrentChunkSize(Long currentChunkSize) {
        this.currentChunkSize = currentChunkSize;
    }

    public Integer getTotalChunks() {
        if (totalChunks != null) {
            return totalChunks;
        }

        validate();
        return (int) Math.ceil(this.getFileSize() * 1.0 / this.getChunkSize());
    }

    public void setTotalChunks(Integer totalChunks) {
        this.totalChunks = totalChunks;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileMD5() {
        return fileMD5;
    }

    public void setFileMD5(String fileMD5) {
        this.fileMD5 = fileMD5;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public long getChunkStartOffset() {
        validate();
        return Math.min((this.getChunkNo() - 1) * this.getChunkSize(), this.getFileSize() - 1);
    }

    public void validate() {
        if (this.getFileId() == null || this.getFileId().isEmpty()) {
            throw new FileUploadException("文件唯一标识fileId不能为空");
        }
        if (this.getChunkNo() == null || this.getChunkNo() < 1) {
            throw new FileUploadException("分块编号chunkNo必须从1开始");
        }
        if (this.getChunkSize() == null || this.getChunkSize() < 1) {
            throw new FileUploadException("预设分块大小chunkSize必须大于0");
        }
        if (this.getFileSize() == null || this.getFileSize() < 1) {
            throw new FileUploadException("文件总大小totalSize必须大于0");
        }
        // 校验分块编号不超过总分块数（如果totalChunks非空）
        if (this.getTotalChunks() != null && this.getChunkNo() > this.getTotalChunks()) {
            throw new FileUploadException("分块编号chunkNo不能超过总分块数totalChunks");
        }
    }

}
