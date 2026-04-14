package com.medical.report.controller;

import com.medical.report.model.MedicalReportResponse;
import com.medical.report.model.QuestionnaireRequest;
import com.medical.report.service.MedicalReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@Slf4j
@RestController
@RequestMapping("/api/medical-report")
@RequiredArgsConstructor
@Tag(name = "Medical Report", description = "医疗报告生成 API")
public class MedicalReportController {

    private final MedicalReportService medicalReportService;

    @PostMapping("/generate")
    @Operation(summary = "生成医疗报告", description = "根据问卷数据和检验报告生成个性化健康建议")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "报告生成成功"),
            @ApiResponse(responseCode = "400", description = "请求参数校验失败"),
            @ApiResponse(responseCode = "500", description = "报告生成失败")
    })
    public ResponseEntity<MedicalReportResponse> generateReport(
            @Valid @RequestBody QuestionnaireRequest request) {

        log.info("Received report generation request for patient: {}", request.getName());

        MedicalReportResponse report = medicalReportService.generateReport(request);

        log.info("Report generated successfully for patient: {}", request.getName());

        return ResponseEntity.ok(report);
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查服务是否正常运行")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Medical Report Service is running");
    }
}
