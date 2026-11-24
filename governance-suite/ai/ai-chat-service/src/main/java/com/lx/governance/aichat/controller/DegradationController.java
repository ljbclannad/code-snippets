package com.lx.governance.aichat.controller;

import com.lx.governance.aichat.degradation.DegradationContext;
import com.lx.governance.aichat.degradation.DegradationLevel;
import com.lx.governance.aichat.degradation.DegradationManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 降级管理 API
 */
@RestController
@RequestMapping("/api/degradation")
@Slf4j
@RequiredArgsConstructor
public class DegradationController {

    private final DegradationManager degradationManager;

    /**
     * 查询当前降级状态
     */
    @GetMapping("/status")
    public ResponseEntity<DegradationStatusResponse> getStatus() {
        DegradationContext context = degradationManager.getCurrentContext();

        DegradationStatusResponse response = new DegradationStatusResponse();
        response.setLevel(context.getLevel().name());
        response.setLevelName(context.getLevel().getName());
        response.setLevelDescription(context.getLevel().getDescription());
        response.setReason(context.getReason());
        response.setTriggerTime(context.getTriggerTime());
        response.setManualTrigger(context.isManualTrigger());
        response.setTriggeredBy(context.getTriggeredBy());
        response.setDegraded(context.getLevel().isDegraded());

        return ResponseEntity.ok(response);
    }

    /**
     * 手动触发降级（管理员操作）
     */
    @PostMapping("/manual")
    public ResponseEntity<Map<String, String>> manualDegrade(
            @RequestBody ManualDegradeRequest request) {

        log.info("手动触发降级 - level: {}, reason: {}, operator: {}",
                request.getLevel(), request.getReason(), request.getOperator());

        try {
            DegradationLevel level = DegradationLevel.valueOf(request.getLevel());
            if (!degradationManager.manualDegradeTo(level, request.getReason(), request.getOperator())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "服务降级总开关已关闭"));
            }

            Map<String, String> response = new HashMap<>();
            response.put("message", "降级触发成功");
            response.put("level", level.name());
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            Map<String, String> response = new HashMap<>();
            response.put("error", "无效的降级级别: " + request.getLevel());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 手动恢复到正常（管理员操作）
     */
    @PostMapping("/recover")
    public ResponseEntity<Map<String, String>> manualRecover(
            @RequestBody RecoverRequest request) {

        log.info("手动恢复服务 - operator: {}", request.getOperator());

        degradationManager.manualRecover(request.getOperator());

        Map<String, String> response = new HashMap<>();
        response.put("message", "服务已恢复正常");
        return ResponseEntity.ok(response);
    }

    /**
     * 查询功能可用性
     */
    @GetMapping("/features")
    public ResponseEntity<Map<String, Boolean>> getFeatureAvailability() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("recommendation", degradationManager.isFeatureEnabled("recommendation"));
        features.put("memory", degradationManager.isFeatureEnabled("memory"));
        features.put("fullContext", degradationManager.isFeatureEnabled("fullContext"));
        features.put("basicContext", degradationManager.isFeatureEnabled("basicContext"));
        features.put("aiApi", degradationManager.isFeatureEnabled("aiApi"));

        return ResponseEntity.ok(features);
    }

    /**
     * 降级状态响应
     */
    public static class DegradationStatusResponse {
        private String level;
        private String levelName;
        private String levelDescription;
        private String reason;
        private java.time.LocalDateTime triggerTime;
        private boolean manualTrigger;
        private String triggeredBy;
        private boolean degraded;

        // Getters and Setters
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public String getLevelName() { return levelName; }
        public void setLevelName(String levelName) { this.levelName = levelName; }
        public String getLevelDescription() { return levelDescription; }
        public void setLevelDescription(String levelDescription) { this.levelDescription = levelDescription; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public java.time.LocalDateTime getTriggerTime() { return triggerTime; }
        public void setTriggerTime(java.time.LocalDateTime triggerTime) { this.triggerTime = triggerTime; }
        public boolean isManualTrigger() { return manualTrigger; }
        public void setManualTrigger(boolean manualTrigger) { this.manualTrigger = manualTrigger; }
        public String getTriggeredBy() { return triggeredBy; }
        public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
        public boolean isDegraded() { return degraded; }
        public void setDegraded(boolean degraded) { this.degraded = degraded; }
    }

    /**
     * 手动降级请求
     */
    public static class ManualDegradeRequest {
        private String level;
        private String reason;
        private String operator;

        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }
    }

    /**
     * 恢复请求
     */
    public static class RecoverRequest {
        private String operator;

        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }
    }
}
