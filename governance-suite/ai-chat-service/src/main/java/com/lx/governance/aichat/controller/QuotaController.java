package com.lx.governance.aichat.controller;

import com.lx.governance.aichat.quota.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配额管理 API
 */
@RestController
@RequestMapping("/api/quota")
@Slf4j
@RequiredArgsConstructor
public class QuotaController {

    private final QuotaService quotaService;

    /**
     * 查询用户配额
     */
    @GetMapping("/{customerId}")
    public ResponseEntity<UserQuota> getQuota(@PathVariable String customerId) {
        log.info("查询用户配额 - customerId: {}", customerId);
        UserQuota quota = quotaService.getUserQuota(customerId);
        return ResponseEntity.ok(quota);
    }

    /**
     * 查询配额使用历史
     */
    @GetMapping("/{customerId}/usage")
    public ResponseEntity<List<QuotaUsageRecord>> getUsageHistory(
            @PathVariable String customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {

        if (startTime == null) {
            startTime = LocalDateTime.now().minusDays(7);
        }
        if (endTime == null) {
            endTime = LocalDateTime.now();
        }

        log.info("查询配额使用历史 - customerId: {}, startTime: {}, endTime: {}",
                customerId, startTime, endTime);

        List<QuotaUsageRecord> records = quotaService.getUsageHistory(customerId, startTime, endTime);
        return ResponseEntity.ok(records);
    }

    /**
     * 查询配额统计
     */
    @GetMapping("/{customerId}/statistics")
    public ResponseEntity<QuotaStatistics> getStatistics(
            @PathVariable String customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {

        if (startTime == null) {
            startTime = LocalDateTime.now().minusDays(7);
        }
        if (endTime == null) {
            endTime = LocalDateTime.now();
        }

        log.info("查询配额统计 - customerId: {}, startTime: {}, endTime: {}",
                customerId, startTime, endTime);

        QuotaStatistics statistics = quotaService.getStatistics(customerId, startTime, endTime);
        return ResponseEntity.ok(statistics);
    }

    /**
     * 更新用户配额（管理员操作）
     */
    @PutMapping("/{customerId}")
    public ResponseEntity<Map<String, String>> updateQuota(
            @PathVariable String customerId,
            @RequestBody UpdateQuotaRequest request) {

        log.info("更新用户配额 - customerId: {}, type: {}, limit: {}",
                customerId, request.getQuotaType(), request.getQuotaLimit());

        QuotaType quotaType = QuotaType.fromCode(request.getQuotaType());
        quotaService.updateQuota(customerId, quotaType, request.getQuotaLimit());

        Map<String, String> response = new HashMap<>();
        response.put("message", "配额更新成功");
        return ResponseEntity.ok(response);
    }

    /**
     * 重置用户配额（管理员操作）
     */
    @PostMapping("/{customerId}/reset")
    public ResponseEntity<UserQuota> resetQuota(@PathVariable String customerId) {
        log.info("重置用户配额 - customerId: {}", customerId);

        UserQuota quota = quotaService.getUserQuota(customerId);
        UserQuota resetQuota = quotaService.resetQuota(quota);

        return ResponseEntity.ok(resetQuota);
    }

    /**
     * 创建默认配额
     */
    @PostMapping("/{customerId}/default")
    public ResponseEntity<UserQuota> createDefaultQuota(@PathVariable String customerId) {
        log.info("创建默认配额 - customerId: {}", customerId);

        UserQuota quota = quotaService.createDefaultQuota(customerId);
        return ResponseEntity.ok(quota);
    }

    /**
     * 更新配额请求
     */
    public static class UpdateQuotaRequest {
        private String quotaType;  // daily, monthly, total
        private Integer quotaLimit;

        public String getQuotaType() {
            return quotaType;
        }

        public void setQuotaType(String quotaType) {
            this.quotaType = quotaType;
        }

        public Integer getQuotaLimit() {
            return quotaLimit;
        }

        public void setQuotaLimit(Integer quotaLimit) {
            this.quotaLimit = quotaLimit;
        }
    }
}
