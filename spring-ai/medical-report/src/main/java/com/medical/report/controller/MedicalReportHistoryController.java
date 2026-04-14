package com.medical.report.controller;

import com.medical.report.entity.MedicalReportHistory;
import com.medical.report.mapper.MedicalReportHistoryMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/medical-report/history")
@RequiredArgsConstructor
@Tag(name = "Report History", description = "医疗报告历史记录 API")
public class MedicalReportHistoryController {

    private final MedicalReportHistoryMapper historyMapper;

    @GetMapping("/{id}")
    @Operation(summary = "根据ID查询报告", description = "通过报告ID获取历史报告详情")
    public ResponseEntity<MedicalReportHistory> getById(@PathVariable Long id) {
        MedicalReportHistory report = historyMapper.findById(id);
        return report != null ? ResponseEntity.ok(report) : ResponseEntity.notFound().build();
    }

    @GetMapping("/patient/{patientName}")
    @Operation(summary = "查询患者所有报告", description = "根据患者姓名查询所有历史报告")
    public ResponseEntity<List<MedicalReportHistory>> getByPatientName(
            @Parameter(description = "患者姓名") @PathVariable String patientName) {
        List<MedicalReportHistory> reports = historyMapper.findByPatientName(patientName);
        return ResponseEntity.ok(reports);
    }

    @GetMapping("/search")
    @Operation(summary = "搜索报告", description = "根据患者姓名模糊搜索")
    public ResponseEntity<List<MedicalReportHistory>> search(
            @Parameter(description = "患者姓名关键词") @RequestParam String keyword) {
        List<MedicalReportHistory> reports = historyMapper.findByPatientNameContaining(keyword);
        return ResponseEntity.ok(reports);
    }

    @GetMapping("/date-range")
    @Operation(summary = "按日期范围查询", description = "查询指定日期范围内的所有报告")
    public ResponseEntity<List<MedicalReportHistory>> getByDateRange(
            @Parameter(description = "开始日期时间，格式：yyyy-MM-dd'T'HH:mm:ss")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @Parameter(description = "结束日期时间，格式：yyyy-MM-dd'T'HH:mm:ss")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        List<MedicalReportHistory> reports = historyMapper.findByCreatedAtBetween(startDate, endDate);
        return ResponseEntity.ok(reports);
    }

    @GetMapping("/recent")
    @Operation(summary = "最近10条报告", description = "获取最近生成的10条报告")
    public ResponseEntity<List<MedicalReportHistory>> getRecent() {
        List<MedicalReportHistory> reports = historyMapper.findTop10ByOrderByCreatedAtDesc();
        return ResponseEntity.ok(reports);
    }

    @GetMapping("/count/{patientName}")
    @Operation(summary = "统计患者报告数量", description = "统计指定患者的历史报告总数")
    public ResponseEntity<Long> countByPatient(@PathVariable String patientName) {
        long count = historyMapper.countByPatientName(patientName);
        return ResponseEntity.ok(count);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除报告", description = "根据ID删除历史报告")
    public ResponseEntity<Void> deleteById(@PathVariable Long id) {
        MedicalReportHistory report = historyMapper.findById(id);
        if (report == null) {
            return ResponseEntity.notFound().build();
        }
        historyMapper.deleteById(id);
        log.info("Deleted report history with id: {}", id);
        return ResponseEntity.noContent().build();
    }
}
