package com.medical.report.controller;

import com.medical.report.model.MedicalReportResponse;
import com.medical.report.model.QuestionnaireRequest;
import com.medical.report.service.MedicalReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@Slf4j
@RestController
@RequestMapping("/api/medical-report")
@RequiredArgsConstructor
public class MedicalReportController {

    private final MedicalReportService medicalReportService;

    @PostMapping("/generate")
    public ResponseEntity<MedicalReportResponse> generateReport(
            @Valid @RequestBody QuestionnaireRequest request) {

        log.info("Received report generation request for patient: {}", request.getName());

        MedicalReportResponse report = medicalReportService.generateReport(request);

        log.info("Report generated successfully for patient: {}", request.getName());

        return ResponseEntity.ok(report);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Medical Report Service is running");
    }
}
