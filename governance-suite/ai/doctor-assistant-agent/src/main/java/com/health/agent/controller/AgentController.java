package com.health.agent.controller;

import com.health.agent.dto.agent.AgentExecutionCommand;
import com.health.agent.engine.ReActAgentEngine;
import com.health.agent.exception.AgentException;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.model.AgentResult;
import com.health.agent.model.AgentSession;
import com.health.agent.model.PendingTask;
import com.health.agent.service.agent.AgentSessionService;
import com.health.agent.service.agent.HighRiskFollowUpService;
import jakarta.servlet.http.HttpServletRequest;
import com.health.agent.service.agent.DoctorRunResumeService;
import com.health.agent.service.agent.PendingTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent 执行", description = "ReAct Agent 执行和会话管理接口")
public class AgentController {

    @Autowired
    private ReActAgentEngine agentEngine;

    @Autowired
    private AgentSessionService sessionService;

    @Autowired
    private PendingTaskService taskService;

    @Autowired
    private DoctorRunResumeService doctorRunResumeService;

    @Autowired
    private HighRiskFollowUpService reminders;

    @Autowired
    private AgentMetrics agentMetrics;

    @Autowired
    private AgentAccessGuard accessGuard;

    @Autowired
    @Qualifier("agentStreamExecutor")
    private ExecutorService streamExecutor;

    @Value("${agent.sse.timeout-millis:120000}")
    private long sseTimeoutMillis;

    @Operation(
        summary = "执行 Agent 任务",
        description = """
            启动一个新的 Agent 会话，Agent 将自动分析目标并执行相应操作。

            **执行流程：**
            1. Agent 分析目标，制定执行计划
            2. 逐步执行工具调用（查询、分析、通知等）
            3. 遇到高风险操作（如起草处方）时暂停，等待医生确认
            4. 返回执行结果或待确认任务

            **返回状态：**
            - SUCCESS: 任务完成
            - PENDING_APPROVAL: 需要医生确认高风险操作
            - ERROR: 执行失败
            - MAX_STEPS_REACHED: 达到最大步数限制
            """,
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = AgentExecuteRequest.class),
                examples = @ExampleObject(
                    name = "减重管理分析示例",
                    value = """
                        {
                          "goal": "患者最近两周体重不下降，帮我判断是否进入平台期并给出下一步建议",
                          "customerId": "C001",
                          "doctorId": "D001"
                        }
                        """
                )
            )
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "执行成功",
            content = @Content(schema = @Schema(implementation = AgentResult.class))),
        @ApiResponse(responseCode = "400", description = "请求参数错误"),
        @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    /**
     * 发起一个新的减重管理 Agent 会话。
     *
     * <p>这是医生侧的主入口。调用后系统会围绕目标语义自动执行 ReAct 多步分析，
     * 可能查询患者档案、体重历史、聊天记录、停滞期信号与依从性信号，并在必要时创建高风险待审批任务。</p>
     *
     * <p>该接口的典型返回包括：</p>
     * <ul>
     *   <li>成功：返回结构化减重管理分析结果</li>
     *   <li>待审批：返回高风险动作对应的待确认任务</li>
     *   <li>降级/失败：返回结构化保守结果和人工接管建议</li>
     * </ul>
     *
     * @param request 执行请求，包含目标、患者 ID 和医生 ID
     * @return Agent 执行结果
     */
    @ResponseStatus(HttpStatus.OK)
    @PostMapping(
        value = "/execute",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter execute(@Valid @RequestBody AgentExecuteRequest request) {
        AgentExecutionCommand command = request.toExecutionCommand();
        log.info("收到Agent执行请求: goal={}, customerId={}",
            command.getGoal(), command.getCustomerId());

        AgentAccessGuard.AccessPermit permit = accessGuard.acquire(command.getDoctorId(), true);
        AtomicReference<CompletableFuture<?>> streamFuture = new AtomicReference<>();
        SseEmitter emitter = createGuardedEmitter(permit, streamFuture);
        try {
            streamFuture.set(CompletableFuture.runAsync(() -> streamToEmitter(emitter, () -> agentEngine.executeStream(
                command.getGoal(),
                command.getCustomerId(),
                command.getDoctorId(),
                event -> sendEvent(emitter, event.getType().name().toLowerCase(), event)
            )), streamExecutor));
        } catch (RejectedExecutionException e) {
            permit.release();
            throw new AgentException("AGENT_STREAM_EXECUTOR_REJECTED: 当前 Agent 流式执行线程池已饱和", e);
        }
        return emitter;
    }

    @Operation(
        summary = "恢复 Agent 执行",
        description = """
            在医生确认高风险操作后，恢复 Agent 执行。

            **使用场景：**
            1. Agent 执行过程中遇到高风险操作（如起草处方）
            2. 系统创建待确认任务并暂停会话
            3. 医生审批任务（approve 或 reject）
            4. 调用此接口恢复执行

            **执行逻辑：**
            - 如果任务已批准：执行该操作，继续 ReAct 循环
            - 如果任务已拒绝：跳过该操作，继续其他步骤
            """,
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = AgentResumeRequest.class),
                examples = @ExampleObject(
                    name = "恢复执行示例",
                    value = """
                        {
                          "sessionId": "1234567890",
                          "approvedTaskId": 101
                        }
                        """
                )
            )
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "恢复成功"),
        @ApiResponse(responseCode = "404", description = "会话或任务不存在"),
        @ApiResponse(responseCode = "400", description = "会话状态不正确或任务未审批")
    })
    @PostMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@Valid @RequestBody AgentResumeRequest request) {
        log.info("恢复Agent执行: sessionId={}, taskId={}",
            request.getSessionId(), request.getApprovedTaskId());

        com.lx.governance.agentplatform.model.ResumeRunRequest resumeRequest =
            doctorRunResumeService.prepareResume(request.getSessionId(), request.getApprovedTaskId());
        AgentSession session = sessionService.getById(resumeRequest.getRunId());

        AgentAccessGuard.AccessPermit permit = accessGuard.acquire(session.getDoctorId(), true);
        AtomicReference<CompletableFuture<?>> streamFuture = new AtomicReference<>();
        SseEmitter emitter = createGuardedEmitter(permit, streamFuture);
        try {
            streamFuture.set(CompletableFuture.runAsync(() -> streamToEmitter(emitter, () -> agentEngine.resumeStream(
                resumeRequest.getRunId(),
                Long.valueOf(resumeRequest.getApprovalTaskId()),
                event -> sendEvent(emitter, event.getType().name().toLowerCase(), event)
            )), streamExecutor));
        } catch (RejectedExecutionException e) {
            permit.release();
            throw new AgentException("AGENT_STREAM_EXECUTOR_REJECTED: 当前 Agent 流式执行线程池已饱和", e);
        }
        return emitter;
    }

    private SseEmitter createGuardedEmitter(
        AgentAccessGuard.AccessPermit permit,
        AtomicReference<CompletableFuture<?>> streamFuture
    ) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMillis);
        emitter.onCompletion(() -> cleanupStream(permit, streamFuture, false));
        emitter.onTimeout(() -> {
            cleanupStream(permit, streamFuture, true);
            emitter.complete();
        });
        emitter.onError(error -> cleanupStream(permit, streamFuture, true));
        return emitter;
    }

    private void cleanupStream(
        AgentAccessGuard.AccessPermit permit,
        AtomicReference<CompletableFuture<?>> streamFuture,
        boolean cancel
    ) {
        permit.release();
        CompletableFuture<?> future = streamFuture.get();
        if (cancel && future != null) {
            future.cancel(true);
        }
    }

    @Operation(
        summary = "查询会话详情",
        description = "根据会话 ID 查询完整的会话信息，包括执行历史、当前状态、消息记录等"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功"),
        @ApiResponse(responseCode = "404", description = "会话不存在")
    })
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<AgentSession> getSession(HttpServletRequest caller,
        @Parameter(description = "会话 ID", required = true, example = "1234567890")
        @PathVariable String sessionId
    ) {
        log.info("查询会话: sessionId={}", sessionId);

        AgentSession session = sessionService.getById(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        if (!canReadSession(session, caller)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(session);
    }

    @Operation(
        summary = "查询医生的会话列表",
        description = "查询指定医生的会话列表，支持按状态过滤（如只查询暂停中的会话）"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功")
    })
    @GetMapping("/sessions")
    public ResponseEntity<List<AgentSession>> listSessions(HttpServletRequest caller,
        @Parameter(description = "医生 ID", required = true, example = "2001")
        @RequestParam String doctorId,
        @Parameter(description = "会话状态过滤（PAUSED/RUNNING/COMPLETED）", example = "PAUSED")
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

        return ResponseEntity.ok(sessions.stream().filter(session -> canReadSession(session, caller)).toList());
    }

    @Operation(
        summary = "查询患者的会话历史",
        description = "查询指定患者的历史会话记录，按时间倒序返回"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功")
    })
    @GetMapping("/sessions/customer/{customerId}")
    public ResponseEntity<List<AgentSession>> listCustomerSessions(HttpServletRequest caller,
        @Parameter(description = "患者 ID", required = true, example = "1001")
        @PathVariable String customerId,
        @Parameter(description = "返回记录数量限制", example = "10")
        @RequestParam(defaultValue = "10") int limit
    ) {
        log.info("查询患者的会话历史: customerId={}, limit={}", customerId, limit);

        List<AgentSession> sessions = sessionService.listByCustomer(customerId, limit);
        return ResponseEntity.ok(sessions.stream().filter(session -> canReadSession(session, caller)).toList());
    }

    @Operation(
        summary = "查询待确认任务详情",
        description = "根据任务 ID 查询待确认任务的详细信息，包括工具名称、输入参数、审批状态等"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @GetMapping("/pending-tasks/{taskId}")
    public ResponseEntity<PendingTask> getTask(HttpServletRequest caller,
        @Parameter(description = "任务 ID", required = true, example = "101")
        @PathVariable Long taskId
    ) {
        log.info("查询待确认任务: taskId={}", taskId);

        PendingTask task = taskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }

        if (!canReadTask(task, caller)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(task);
    }

    private boolean canReadSession(AgentSession session, HttpServletRequest caller) {
        if (!HighRiskFollowUpService.TITLE.equals(session.getGoal()) || isAssignedDoctor(session.getDoctorId(), caller)) {
            return true;
        }
        // 旧会话也可能使用相同的自然语言目标，以持久化任务类型确认是否属于本场景。
        return taskService.listBySession(session.getId()).stream()
                .noneMatch(task -> HighRiskFollowUpService.TOOL.equals(task.getToolName()));
    }

    private boolean isAssignedDoctor(String doctorId, HttpServletRequest caller) {
        return caller.getUserPrincipal() != null && caller.isUserInRole("DOCTOR")
                && caller.getUserPrincipal().getName().equals(doctorId);
    }

    private boolean canReadTask(PendingTask task, HttpServletRequest caller) {
        return !HighRiskFollowUpService.TOOL.equals(task.getToolName())
                || isAssignedDoctor(task.getDoctorId(), caller);
    }

    private void streamToEmitter(SseEmitter emitter, Runnable streamTask) {
        try {
            streamTask.run();
            emitter.complete();
        } catch (Exception e) {
            log.error("Agent流式输出异常", e);
            emitter.completeWithError(e);
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (Exception e) {
            throw new RuntimeException("发送SSE事件失败", e);
        }
    }

    @Operation(
        summary = "查询医生的待确认任务列表",
        description = "查询指定医生的所有待确认任务，通常用于医生工作台展示待审批事项"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功")
    })
    @GetMapping("/pending-tasks")
    public ResponseEntity<List<PendingTask>> listPendingTasks(HttpServletRequest caller,
        @Parameter(description = "医生 ID", required = true, example = "2001")
        @RequestParam String doctorId
    ) {
        log.info("查询医生的待确认任务: doctorId={}", doctorId);

        List<PendingTask> tasks = taskService.listPendingByDoctor(doctorId);
        return ResponseEntity.ok(tasks.stream().filter(task -> canReadTask(task, caller)).toList());
    }

    @Operation(
        summary = "查询会话的待确认任务列表",
        description = "查询指定会话的所有待确认任务，用于查看某个 Agent 执行过程中产生的所有高风险操作"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "查询成功")
    })
    @GetMapping("/sessions/{sessionId}/pending-tasks")
    public ResponseEntity<List<PendingTask>> listSessionTasks(HttpServletRequest caller,
        @Parameter(description = "会话 ID", required = true, example = "1234567890")
        @PathVariable String sessionId
    ) {
        log.info("查询会话的待确认任务: sessionId={}", sessionId);

        List<PendingTask> tasks = taskService.listBySession(sessionId);
        return ResponseEntity.ok(tasks.stream().filter(task -> canReadTask(task, caller)).toList());
    }

    @Operation(
        summary = "批准任务",
        description = """
            医生批准高风险操作任务。批准后需要调用 /api/agent/resume 接口恢复 Agent 执行。

            **批准流程：**
            1. 查询待确认任务列表
            2. 审查任务详情（工具名称、输入参数）
            3. 调用此接口批准任务
            4. 调用 /api/agent/resume 恢复执行
            """,
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = TaskApprovalRequest.class),
                examples = @ExampleObject(
                    name = "批准示例",
                    value = """
                        {
                          "reviewComment": "处方合理，批准执行"
                        }
                        """
                )
            )
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "批准成功"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @PostMapping("/pending-tasks/{taskId}/approve")
    public ResponseEntity<TaskApprovalResponse> approveTask(HttpServletRequest caller,
        @Parameter(description = "任务 ID", required = true, example = "101")
        @PathVariable Long taskId,
        @RequestBody TaskApprovalRequest request
    ) {
        PendingTask task = taskService.getById(taskId);
        if (task != null && HighRiskFollowUpService.TOOL.equals(task.getToolName())) {
            String doctorId = FollowUpReminderController.identity(caller, "DOCTOR");
            reminders.review(taskId, doctorId, true, request.getReviewComment());
            return ResponseEntity.ok(TaskApprovalResponse.builder().success(true)
                    .taskId(taskId).message("随访提醒已投递至患者站内收件箱").build());
        }

        doctorRunResumeService.approveTask(taskId, request.getReviewComment());
        agentMetrics.recordApprovalDecision("approved", "pending_task");

        return ResponseEntity.ok(TaskApprovalResponse.builder()
            .success(true)
            .message("任务已批准")
            .taskId(taskId)
            .build());
    }

    @Operation(
        summary = "拒绝任务",
        description = """
            医生拒绝高风险操作任务。拒绝后需要调用 /api/agent/resume 接口，Agent 将跳过该操作继续执行。

            **拒绝流程：**
            1. 查询待确认任务列表
            2. 审查任务详情
            3. 调用此接口拒绝任务
            4. 调用 /api/agent/resume 恢复执行（Agent 会跳过该操作）
            """,
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = TaskApprovalRequest.class),
                examples = @ExampleObject(
                    name = "拒绝示例",
                    value = """
                        {
                          "reviewComment": "剂量偏大，需要重新评估"
                        }
                        """
                )
            )
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "拒绝成功"),
        @ApiResponse(responseCode = "404", description = "任务不存在")
    })
    @PostMapping("/pending-tasks/{taskId}/reject")
    public ResponseEntity<TaskApprovalResponse> rejectTask(HttpServletRequest caller,
        @Parameter(description = "任务 ID", required = true, example = "101")
        @PathVariable Long taskId,
        @RequestBody TaskApprovalRequest request
    ) {
        PendingTask task = taskService.getById(taskId);
        if (task != null && HighRiskFollowUpService.TOOL.equals(task.getToolName())) {
            String doctorId = FollowUpReminderController.identity(caller, "DOCTOR");
            reminders.review(taskId, doctorId, false, request.getReviewComment());
            return ResponseEntity.ok(TaskApprovalResponse.builder().success(true)
                    .taskId(taskId).message("随访提醒已拒绝").build());
        }

        doctorRunResumeService.rejectTask(taskId, request.getReviewComment());
        agentMetrics.recordApprovalDecision("rejected", "pending_task");

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
    @Schema(description = "Agent 执行请求")
    public static class AgentExecuteRequest {
        @Schema(description = "执行目标（自然语言描述）", example = "患者最近体重增加明显，帮我分析一下原因并给出建议", required = true)
        @NotBlank(message = "goal 不能为空")
        private String goal;

        @Schema(description = "患者 ID", example = "1001", required = true)
        @NotBlank(message = "customerId 不能为空")
        private String customerId;

        @Schema(description = "医生 ID", example = "2001", required = true)
        @NotBlank(message = "doctorId 不能为空")
        private String doctorId;

        public AgentExecutionCommand toExecutionCommand() {
            return AgentExecutionCommand.builder()
                    .goal(goal)
                    .customerId(customerId)
                    .doctorId(doctorId)
                    .build();
        }
    }

    /**
     * Agent 恢复请求
     */
    @Data
    @Schema(description = "Agent 恢复执行请求")
    public static class AgentResumeRequest {
        @Schema(description = "会话 ID", example = "1234567890", required = true)
        @NotBlank(message = "sessionId 不能为空")
        private String sessionId;

        @Schema(description = "已批准的任务 ID", example = "101", required = true)
        @NotNull(message = "approvedTaskId 不能为空")
        private Long approvedTaskId;
    }

    /**
     * 任务审批请求
     */
    @Data
    @Schema(description = "任务审批请求")
    public static class TaskApprovalRequest {
        @Schema(description = "审批意见", example = "处方合理，批准执行")
        private String reviewComment;
    }

    /**
     * 任务审批响应
     */
    @Data
    @lombok.Builder
    @Schema(description = "任务审批响应")
    public static class TaskApprovalResponse {
        @Schema(description = "是否成功", example = "true")
        private boolean success;

        @Schema(description = "响应消息", example = "任务已批准")
        private String message;

        @Schema(description = "任务 ID", example = "101")
        private Long taskId;
    }
}
