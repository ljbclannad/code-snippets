package com.lx.governance.knowledgebase.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.util.List;

/**
 * 语义检索请求
 */
@Data
public class SearchRequest {

    @NotBlank(message = "查询文本不能为空")
    private String query;

    @Min(value = 1, message = "topK不能小于1")
    @Max(value = 20, message = "topK不能大于20")
    private Integer topK = 5;

    @Min(value = 0, message = "相似度阈值不能小于0")
    @Max(value = 1, message = "相似度阈值不能大于1")
    private Double similarityThreshold = 0.6;

    /**
     * 是否使用 rerank
     */
    private Boolean rerank = false;

    /**
     * 是否开启混合检索
     */
    private Boolean hybridSearch = true;

    /**
     * 是否开启查询改写
     */
    private Boolean useRewrite = false;

    /**
     * 过滤条件
     */
    private String titleKeyword;

    private List<String> tags;

    private String retrievalProfile;

    private List<String> documentTypes;

    private List<String> diseases;

    private List<String> populations;

    private List<String> regions;

    private List<String> sourceTypes;

    private String version;

    private String publishDateFrom;

    private String businessDomain;

    private String source;

    private String applicableScope;

    private String trustLevel;

    /**
     * 可选的文档ID过滤
     */
    private List<String> docIds;
}
