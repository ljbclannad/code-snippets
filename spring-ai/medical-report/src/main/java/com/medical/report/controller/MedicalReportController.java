package com.medical.report.controller;

import com.medical.report.model.MedicalReportResponse;
import com.medical.report.model.MedicalReportResponseWithMetadata;
import com.medical.report.model.QuestionnaireRequest;
import com.medical.report.service.MedicalReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api/medical-report")
@RequiredArgsConstructor
@Tag(name = "Medical Report", description = "医疗报告生成 API")
public class MedicalReportController {

    private final MedicalReportService medicalReportService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @PostMapping("/generate")
    @Operation(summary = "生成医疗报告", description = "根据问卷数据和检验报告生成个性化健康建议，返回报告和Token使用统计")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "报告生成成功"),
            @ApiResponse(responseCode = "400", description = "请求参数校验失败"),
            @ApiResponse(responseCode = "500", description = "报告生成失败")
    })
    public ResponseEntity<MedicalReportResponseWithMetadata> generateReport(
            @Valid @RequestBody QuestionnaireRequest request) {

        log.info("Received report generation request for patient: {}", request.getName());

        MedicalReportResponseWithMetadata response = medicalReportService.generateReport(request);

        log.info("Report generated successfully for patient: {} - Tokens: {}/{}, Cost: ${}, Time: {}ms",
                request.getName(),
                response.getTokenUsage().getInputTokens(),
                response.getTokenUsage().getOutputTokens(),
                String.format("%.6f", response.getTokenUsage().getEstimatedCost()),
                response.getTokenUsage().getResponseTimeMs());

        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式生成医疗报告", description = "使用 SSE 流式返回报告生成过程，提升用户体验")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "开始流式生成"),
            @ApiResponse(responseCode = "400", description = "请求参数校验失败"),
            @ApiResponse(responseCode = "500", description = "流式生成失败")
    })
    public SseEmitter generateReportStream(@Valid @RequestBody QuestionnaireRequest request) {
        log.info("Received streaming report generation request for patient: {}", request.getName());

        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        executorService.execute(() -> {
            try {
                medicalReportService.generateReportStream(
                        request,
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("chunk")
                                        .data(chunk));
                            } catch (IOException e) {
                                log.error("Failed to send chunk", e);
                                emitter.completeWithError(e);
                            }
                        },
                        report -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("complete")
                                        .data(report));
                                emitter.complete();
                                log.info("Streaming completed for patient: {}", request.getName());
                            } catch (IOException e) {
                                log.error("Failed to send completion", e);
                                emitter.completeWithError(e);
                            }
                        }
                );
            } catch (Exception e) {
                log.error("Streaming generation failed for patient: {}", request.getName(), e);
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> {
            log.warn("SSE timeout for patient: {}", request.getName());
            emitter.complete();
        });

        emitter.onError(e -> {
            log.error("SSE error for patient: {}", request.getName(), e);
        });

        return emitter;
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查服务是否正常运行")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Medical Report Service is running");
    }
}
