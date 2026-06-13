package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 必需字段配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequiredField {

    /**
     * 字段路径，如 "healthProfile.bloodSugar"
     */
    private String path;

    /**
     * 字段名称
     */
    private String name;

    /**
     * 优先级
     */
    private DiagnosisResult.FieldPriority priority;

    /**
     * 追问问题
     */
    private String question;
}
