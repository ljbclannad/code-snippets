package com.lx.governance.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档实体
 */
@Data
@TableName("knowledge_document")
public class KnowledgeDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 知识库ID
     */
    private String kbId;

    /**
     * 文档ID
     */
    private String docId;

    /**
     * 标题
     */
    private String title;

    /**
     * 标签，逗号分隔
     */
    private String tags;

    /**
     * 业务域
     */
    private String businessDomain;

    /**
     * 来源
     */
    private String source;

    /**
     * 适用范围
     */
    private String applicableScope;

    /**
     * 可信级别
     */
    private String trustLevel;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件类型：pdf/md/markdown/txt
     */
    private String fileType;

    /**
     * 文件大小(字节)
     */
    private Long fileSize;

    /**
     * 存储路径
     */
    private String filePath;

    /**
     * 文件内容哈希
     */
    private String contentHash;

    /**
     * 状态：UPLOADED/PROCESSING/PARSE_FAILED/SUSPECTED_DUPLICATE/DEDUPLICATED/ACTIVE/ARCHIVED/FAILED
     */
    private String status;

    /**
     * 是否有效
     */
    private Boolean isActive;

    /**
     * 是否归档
     */
    private Boolean isArchived;

    /**
     * 去重状态
     */
    private String duplicateStatus;

    /**
     * 命中的重复文档
     */
    private String duplicateTargetDocId;

    /**
     * 重复分数
     */
    private Double duplicateScore;

    /**
     * 切片数量
     */
    private Integer chunkCount;

    /**
     * 处理开始时间
     */
    private LocalDateTime processStartTime;

    /**
     * 处理结束时间
     */
    private LocalDateTime processEndTime;

    /**
     * 当前处理阶段
     */
    private String processingStage;

    /**
     * 处理进度
     */
    private Integer processingProgress;

    /**
     * 重试次数
     */
    private Integer retryCount;

    /**
     * 最近重试时间
     */
    private LocalDateTime lastRetryTime;

    /**
     * 最近失败阶段
     */
    private String lastErrorStage;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建者
     */
    private String createdBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 上传时间
     */
    private LocalDateTime uploadTime;
}
