package com.lx.governance.knowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 搜索结果项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {

    /**
     * 切片ID
     */
    private String chunkId;

    /**
     * 文档ID
     */
    private String docId;

    private String title;

    private String tags;

    private String businessDomain;

    private String source;

    private String applicableScope;

    private String trustLevel;

    /**
     * 内容
     */
    private String content;

    /**
     * 相似度分数
     */
    private Double score;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;

    private String hitReason;
}
