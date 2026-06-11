package com.lx.governance.knowledgebase.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 文档段落
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Paragraph {

    /**
     * 段落内容
     */
    private String content;

    /**
     * 元数据 (页码、章节、标题等)
     */
    private Map<String, Object> metadata;
}
