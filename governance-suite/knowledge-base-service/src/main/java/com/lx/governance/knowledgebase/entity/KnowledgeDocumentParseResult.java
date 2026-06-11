package com.lx.governance.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_document_parse_result")
public class KnowledgeDocumentParseResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String docId;

    private String parseStatus;

    private String summary;

    private String keywords;

    private String suggestedTags;

    private String extendedMetadata;

    private String semanticDedupResult;

    private String parseErrorMessage;

    private LocalDateTime processedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
