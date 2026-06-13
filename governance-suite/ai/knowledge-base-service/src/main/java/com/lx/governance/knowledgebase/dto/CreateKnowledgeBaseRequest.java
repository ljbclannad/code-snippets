package com.lx.governance.knowledgebase.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

/**
 * 创建知识库请求
 */
@Data
public class CreateKnowledgeBaseRequest {

    @NotBlank(message = "知识库名称不能为空")
    private String name;

    private String description;

    @Min(value = 100, message = "切片大小不能小于100")
    @Max(value = 2000, message = "切片大小不能大于2000")
    private Integer chunkSize = 500;

    @Min(value = 0, message = "重叠大小不能小于0")
    @Max(value = 500, message = "重叠大小不能大于500")
    private Integer chunkOverlap = 50;

    @Min(value = 0, message = "语义相似度阈值不能小于0")
    @Max(value = 1, message = "语义相似度阈值不能大于1")
    private Double semanticThreshold = 0.7;

    private String createdBy;
}
