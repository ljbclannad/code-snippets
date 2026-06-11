package com.lx.governance.knowledgebase.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 文档切片
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    /**
     * 切片ID
     */
    private String chunkId;

    /**
     * 文档ID
     */
    private String docId;

    /**
     * 切片内容
     */
    private String content;

    /**
     * 切片索引
     */
    private Integer chunkIndex;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;
}
