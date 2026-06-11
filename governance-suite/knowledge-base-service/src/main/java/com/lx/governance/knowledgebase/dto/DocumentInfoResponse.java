package com.lx.governance.knowledgebase.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档信息响应
 */
@Data
public class DocumentInfoResponse {

    private String docId;
    private String kbId;
    private String title;
    private String tags;
    private String businessDomain;
    private String source;
    private String applicableScope;
    private String trustLevel;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String contentHash;
    private String status;
    private Boolean isActive;
    private Boolean isArchived;
    private String duplicateStatus;
    private String duplicateTargetDocId;
    private Double duplicateScore;
    private Integer chunkCount;
    private String processingStage;
    private Integer processingProgress;
    private Integer retryCount;
    private LocalDateTime lastRetryTime;
    private String lastErrorStage;
    private String createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime processStartTime;
    private LocalDateTime processEndTime;
    private String errorMessage;
    private LocalDateTime uploadTime;
}
