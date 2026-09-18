package com.lx.governance.aichat.evaluation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 仅用于显式测试采集，不作为业务任务或权限输入。 */
public record EvaluationBinding(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,79}") String batchId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,79}") String caseId,
        @Min(1) @Max(10) int trial) { }
