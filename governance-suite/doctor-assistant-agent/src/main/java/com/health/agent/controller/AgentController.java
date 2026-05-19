package com.health.agent.controller;

import com.health.agent.engine.ReActAgentEngine;
import com.health.agent.model.AgentResult;
import com.health.agent.model.AgentSession;
import com.health.agent.model.PendingTask;
import com.health.agent.service.AgentSessionService;
import com.health.agent.service.PendingTaskService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    @Autowired
    private ReActAgentEngine agentEngine;

    @Autowired
    private AgentSessionService sessionService;

    @Autowired
    private PendingTaskService taskService;

    /**
     * 执行 Agent 任务
     */
    @PostMapping("/execute")
    public ResponseEntity<AgentResult> execute(@RequestBody AgentExecuteRequest request) {
        log.info("收到Agent执行请求: goal={}, customerId={}",
            request.getGoal(), request.getCustomerId());

        AgentResult result = agentEngine.execute(
            request.getGoal(),
            request.getCustomerId(),
            request.getDoctorId()
        );

        return ResponseEntity.ok(result);
    }

    /**
     * 恢复 Agent 执行（人工确认后）
     */
    @PostMapping("/resume")
    public ResponseEntity<AgentResult> resume(@RequestBody AgentResumeRequest request) {
        log.info("恢复Agent执行: sessionId={}, taskId={}",
            request.getSessionId(), request.getApprovedTaskId());

        AgentResult result = agentEngine.resume(
            request.getSessionId(),
            request.getApprovedTaskId()
        );

        return ResponseEntity.ok(result);
    }

    /**
     * 查询会话详情
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<AgentSession> getSession(@PathVariable String sessionId) {
        log.info("查询会话: sessionId={}", sessionId);

        AgentSession session = sessionService.getById(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(session);
    }

    /**
     * 查询医生的待处理会话列表
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<AgentSession>> listSessions(
        @RequestParam String doctorId,
        @RequestParam(required = false) String status
    ) {
        log.info("查询医生的会话列表: doctorId={}, status={}", doctorId, status);

        List<AgentSession> sessions;
        if ("PAUSED".equals(status)) {
            sessions = sessionService.listPendingByDoctor(doctorId);
        } else {
            // 可以扩展支持其他状态查询
            sessions = sessionService.listPendingByDoctor(doctorId);
        }

        return ResponseEntity.ok(sessions);
    }

    /**
     * 查询患者的会话历史
     */
    @GetMapping("/sessions/customer/{customerId}")
    public ResponseEntity<List<AgentSession>> listCustomerSessions(
        @PathVariable String customerId,
        @RequestParam(defaultValue = "10") int limit
    ) {
        log.info("查询患者的会话历史: customerId={}, limit={}", customerId, limit);

        List<AgentSession> sessions = sessionService.listByCustomer(customerId, limit);
        return ResponseEntity.ok(sessions);
    }

    /**
     * 查询待确认任务详情
     */
    @GetMapping("/pending-tasks/{taskId}")
    public ResponseEntity<PendingTask> getTask(@PathVariable Long taskId) {
        log.info("查询待确认任务: taskId={}", taskId);

        PendingTask task = taskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(task);
    }

    /**
     * 查询医生的待确认任务列表
     */
    @GetMapping("/pending-tasks")
    public ResponseEntity<List<PendingTask>> listPendingTasks(@RequestParam String doctorId) {
        log.info("查询医生的待确认任务: doctorId={}", doctorId);

        List<PendingTask> tasks = taskService.listPendingByDoctor(doctorId);
        return ResponseEntity.ok(tasks);
    }

    /**
     * 查询会话的待确认任务列表
     */
    @GetMapping("/sessions/{sessionId}/pending-tasks")
    public ResponseEntity<List<PendingTask>> listSessionTasks(@PathVariable String sessionId) {
        log.info("查询会话的待确认任务: sessionId={}", sessionId);

        List<PendingTask> tasks = taskService.listBySession(sessionId);
        return ResponseEntity.ok(tasks);
    }

    /**
     * 批准任务
     */
    @PostMapping("/pending-tasks/{taskId}/approve")
    public ResponseEntity<TaskApprovalResponse> approveTask(
        @PathVariable Long taskId,
        @RequestBody TaskApprovalRequest request
    ) {
        log.info("批准任务: taskId={}, comment={}", taskId, request.getReviewComment());

        taskService.approve(taskId, request.getReviewComment());

        return ResponseEntity.ok(TaskApprovalResponse.builder()
            .success(true)
            .message("任务已批准")
            .taskId(taskId)
            .build());
    }

    /**
     * 拒绝任务
     */
    @PostMapping("/pending-tasks/{taskId}/reject")
    public ResponseEntity<TaskApprovalResponse> rejectTask(
        @PathVariable Long taskId,
        @RequestBody TaskApprovalRequest request
    ) {
        log.info("拒绝任务: taskId={}, comment={}", taskId, request.getReviewComment());

        taskService.reject(taskId, request.getReviewComment());

        return ResponseEntity.ok(TaskApprovalResponse.builder()
            .success(true)
            .message("任务已拒绝")
            .taskId(taskId)
            .build());
    }

    // ==================== Request/Response VO ====================

    /**
     * Agent 执行请求
     */
    @Data
    public static class AgentExecuteRequest {
        private String goal;
        private String customerId;
        private String doctorId;
    }

    /**
     * Agent 恢复请求
     */
    @Data
    public static class AgentResumeRequest {
        private String sessionId;
        private Long approvedTaskId;
    }

    /**
     * 任务审批请求
     */
    @Data
    public static class TaskApprovalRequest {
        private String reviewComment;
    }

    /**
     * 任务审批响应
     */
    @Data
    @lombok.Builder
    public static class TaskApprovalResponse {
        private boolean success;
        private String message;
        private Long taskId;
    }
}
