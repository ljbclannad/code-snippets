package com.health.agent.vector;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 病例向量实体
 */
@Data
public class CaseVector {

    /**
     * 病例ID
     */
    private String caseId;

    /**
     * 患者ID
     */
    private String customerId;

    /**
     * 病例描述
     */
    private String description;

    /**
     * 诊断结果
     */
    private String diagnosis;

    /**
     * 症状列表
     */
    private List<String> symptoms;

    /**
     * 慢性病列表
     */
    private List<String> chronicDiseases;

    /**
     * 处方药物
     */
    private List<String> medications;

    /**
     * 向量表示（768维）
     */
    private List<Float> embedding;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 相似度分数（检索时使用）
     */
    private Float similarity;
}
