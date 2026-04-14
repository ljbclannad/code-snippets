package com.medical.report.controller;

import com.medical.report.model.MedicalReportResponseWithMetadata;
import com.medical.report.model.QuestionnaireRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
@Tag(name = "Demo", description = "Spring AI 功能演示 API")
public class DemoController {

    private final com.medical.report.service.MedicalReportService medicalReportService;

    @PostMapping("/function-calling")
    @Operation(summary = "Function Calling 演示",
               description = "演示 AI 自动调用 queryPatientHistory 函数查询历史报告")
    public ResponseEntity<MedicalReportResponseWithMetadata> demoFunctionCalling() {
        QuestionnaireRequest request = QuestionnaireRequest.builder()
                .name("董丽莉")
                .age("51")
                .gender("女")
                .weight("60")
                .height("160")
                .bmi("23.44")
                .familyDiseases(List.of("肥胖"))
                .conditions(List.of("PCOS 多囊卵巢综合症"))
                .healthGoals(List.of("肠胃健康", "睡眠质量"))
                .build();

        log.info("Demo: Function Calling - AI will automatically query patient history if needed");
        return ResponseEntity.ok(medicalReportService.generateReport(request));
    }

    @GetMapping("/features")
    @Operation(summary = "查看已启用的 Spring AI 功能")
    public ResponseEntity<FeatureStatus> getFeatures() {
        return ResponseEntity.ok(FeatureStatus.builder()
                .functionCalling(true)
                .structuredOutput(true)
                .promptTemplate(true)
                .tokenCounting(true)
                .retryMechanism(true)
                .circuitBreaker(true)
                .streaming(true)
                .caching(true)
                .build());
    }

    @lombok.Data
    @lombok.Builder
    public static class FeatureStatus {
        private boolean functionCalling;
        private boolean structuredOutput;
        private boolean promptTemplate;
        private boolean tokenCounting;
        private boolean retryMechanism;
        private boolean circuitBreaker;
        private boolean streaming;
        private boolean caching;
    }
}
