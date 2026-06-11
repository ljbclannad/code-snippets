package com.lx.governance.knowledgebase.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库响应
 */
@Data
public class KnowledgeBaseResponse {

    private String kbId;
    private String name;
    private String description;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Double semanticThreshold;
    private String milvusCollection;
    private Integer embeddingDimension;
    private String status;
    private Integer documentCount;
    private Integer chunkCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String createdBy;
}
