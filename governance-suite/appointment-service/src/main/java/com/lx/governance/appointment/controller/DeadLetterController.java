package com.lx.governance.appointment.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lx.governance.appointment.domain.DeadLetterMessage;
import com.lx.governance.appointment.service.DeadLetterService;
import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 死信消息管理接口
 */
@RestController
@RequestMapping("/api/dead-letter")
@Slf4j
@RequiredArgsConstructor
public class DeadLetterController {

    private final DeadLetterService deadLetterService;

    /**
     * 查询待处理的死信消息
     */
    @GetMapping("/pending")
    public ApiResponse<List<DeadLetterMessage>> getPendingMessages(
            @RequestParam(defaultValue = "100") int limit) {
        List<DeadLetterMessage> messages = deadLetterService.findPendingMessages(limit);
        return ApiResponse.ok(messages, Trace.getOrCreate());
    }

    /**
     * 手动重试死信消息
     */
    @PostMapping("/{id}/retry")
    public ApiResponse<Boolean> retryMessage(@PathVariable Long id) {
        boolean success = deadLetterService.retryDeadLetter(id);
        if (success) {
            return ApiResponse.ok(true, Trace.getOrCreate());
        } else {
            return ApiResponse.fail("RETRY_FAILED", "重试失败", Trace.getOrCreate());
        }
    }

    /**
     * 标记死信消息为已解决
     */
    @PostMapping("/{id}/resolve")
    public ApiResponse<Void> resolveMessage(@PathVariable Long id) {
        deadLetterService.markAsResolved(id);
        return ApiResponse.ok(null, Trace.getOrCreate());
    }

    /**
     * 获取死信消息统计
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("pending", deadLetterService.countByStatus("PENDING"));
        stats.put("retrying", deadLetterService.countByStatus("RETRYING"));
        stats.put("resolved", deadLetterService.countByStatus("RESOLVED"));
       stats.put("failed", deadLetterService.countByStatus("FAILED"));
        return ApiResponse.ok(stats, Trace.getOrCreate());
    }
}
