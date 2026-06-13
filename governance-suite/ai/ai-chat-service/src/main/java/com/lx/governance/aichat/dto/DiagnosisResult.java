package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 画像诊断结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisResult {

    /**
     * 是否需要补全
     */
    private boolean needsCompletion;

    /**
     * 缺失的关键字段
     */
    private List<MissingField> missingFields;

    /**
     * 诊断原因
     */
    private String reason;

    /**
     * 置信度（0-1）
     */
    private double confidence;

    /**
     * 用户意图
     */
    private String intent;

    /**
     * 缺失字段信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MissingField {

        /**
         * 字段路径，如 "healthProfile.bloodSugar"
         */
        private String fieldPath;

        /**
         * 字段名称
         */
        private String fieldName;

        /**
         * 优先级
         */
        private FieldPriority priority;

        /**
         * 建议的追问问题
         */
        private String suggestedQuestion;
    }

    /**
     * 字段优先级枚举
     */
    public enum FieldPriority {
        /**
         * 必须字段，缺失则无法回答
         */
        HIGH,

        /**
         * 重要字段，缺失会影响回答质量
         */
        MEDIUM,

        /**
         * 可选字段，缺失影响较小
         */
        LOW
    }
}
