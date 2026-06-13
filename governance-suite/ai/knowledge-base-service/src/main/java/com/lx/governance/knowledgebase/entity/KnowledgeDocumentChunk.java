package com.lx.governance.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_document_chunk")
public class KnowledgeDocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String chunkId;

    private String kbId;

    private String docId;

    private Integer chunkIndex;

    private String title;

    private String tags;

    private String businessDomain;

    private String source;

    private String applicableScope;

    private String trustLevel;

    private Boolean isActive;

    private Boolean isArchived;

    private String duplicateStatus;

    private String content;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
