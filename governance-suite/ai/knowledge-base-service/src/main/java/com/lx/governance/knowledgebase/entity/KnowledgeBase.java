package com.lx.governance.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库实体
 */
@Data
@TableName("knowledge_base")
public class KnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 知识库ID
     */
    private String kbId;

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 描述
     */
    private String description;

    /**
     * 切片大小
     */
    private Integer chunkSize;

    /**
     * 重叠大小
     */
    private Integer chunkOverlap;

    /**
     * 语义相似度阈值
     */
    private Double semanticThreshold;

    /**
     * Milvus collection 名称
     */
    private String milvusCollection;

    /**
     * 向量维度
     */
    private Integer embeddingDimension;

    /**
     * 状态：ACTIVE/DELETED
     */
    private String status;

    /**
     * 文档数量
     */
    private Integer documentCount;

    /**
     * 切片数量
     */
    private Integer chunkCount;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 创建者
     */
    private String createdBy;
}
