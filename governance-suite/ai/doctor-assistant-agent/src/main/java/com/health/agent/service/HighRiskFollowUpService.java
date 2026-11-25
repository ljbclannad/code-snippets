package com.health.agent.service;

import com.alibaba.fastjson2.JSON;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentResult;
import com.health.agent.model.PendingTask;
import com.health.agent.model.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 医生批准后，站内收件箱与审批记录在同一次数据库提交中生效。 */
@Service
@RequiredArgsConstructor
public class HighRiskFollowUpService {
    public static final String TOOL = "high_risk_follow_up_reminder";
    public static final String TITLE = "高风险随访提醒";
    private static final String BODY = "请联系负责随访的医生，反馈目前的情况。若有紧急不适，请立即就医，不要等待线上回复。";

    private final JdbcTemplate jdbc;
    private final AgentSessionService sessions;
    private final AgentSessionWorkflowService workflow;
    private final PendingTaskService tasks;
    private final AgentAuditService audit;

    public record Receipt(long taskId, String status) {}
    public record InboxMessage(long receiptId, String message, LocalDateTime deliveredAt) {}

    @Transactional
    public Receipt open(String customerId, String requestId, String conversationId, String evidence, String doctorId) {
        jdbc.update("""
                INSERT INTO agent_follow_up_reminder (customer_id, request_id, conversation_id, body)
                VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE request_id = request_id
                """, customerId, requestId, conversationId, BODY);
        Long taskId = jdbc.queryForObject("""
                SELECT pending_task_id FROM agent_follow_up_reminder
                WHERE customer_id = ? AND request_id = ? FOR UPDATE
                """, Long.class, customerId, requestId);
        if (taskId != null) {
            PendingTask task = tasks.getById(taskId);
            var input = JSON.parseObject(task.getInputJson());
            if (!conversationId.equals(input.getString("conversationId")) || !evidence.equals(input.getString("patientMessage"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求编号已用于另一条消息");
            }
            return new Receipt(taskId, task.getStatus());
        }

        var session = sessions.createSession(customerId, doctorId, TITLE, conversationId);
        var task = PendingTask.builder().agentSessionId(session.getId()).customerId(customerId).doctorId(doctorId)
                .toolName(TOOL).toolDescription(TITLE).reason("患者消息命中高风险规则，请核实原文并决定是否发送站内提醒")
                .inputJson(JSON.toJSONString(Map.of("conversationId", conversationId, "patientMessage", evidence, "reminder", BODY)))
                .status(TaskStatus.PENDING.name()).createdAt(LocalDateTime.now()).build();
        workflow.pauseSessionForApproval(session.getId(), List.of(), List.of(),
                AgentContext.initialize(customerId, doctorId, TITLE), task, TITLE,
                AgentResult.pendingApproval(session.getId(), task, List.of(), 0), 0, 0);
        jdbc.update("UPDATE agent_follow_up_reminder SET pending_task_id = ? WHERE customer_id = ? AND request_id = ?",
                task.getId(), customerId, requestId);
        return new Receipt(task.getId(), task.getStatus());
    }

    @Transactional
    public Receipt review(long taskId, String doctorId, boolean approve, String comment) {
        // 与创建流程以同一行串行化。重复审批可以安全回放，互斥决定只能成功一个。
        var ids = jdbc.queryForList("SELECT pending_task_id FROM agent_follow_up_reminder WHERE pending_task_id = ? FOR UPDATE",
                Long.class, taskId);
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "随访任务不存在");
        }
        PendingTask task = tasks.getById(taskId);
        if (!task.getDoctorId().equals(doctorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅责任医生可处理此提醒");
        }
        String decision = (approve ? TaskStatus.APPROVED : TaskStatus.REJECTED).name();
        if (decision.equals(task.getStatus())) {
            return new Receipt(taskId, decision);
        }
        if (!TaskStatus.PENDING.name().equals(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "此提醒已有审批决定");
        }
        if (!approve && (comment == null || comment.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写拒绝理由");
        }
        // 这是工作台的站内投递。只写真实审批和投递审计，避免生成模型运行的结果与归因。
        int changed = jdbc.update("""
                UPDATE agent_pending_task SET status = ?, review_comment = ?, reviewed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tool_name = ? AND status = 'PENDING'
                """, decision, comment, taskId, TOOL);
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "随访审批状态已变更");
        }
        audit.recordTaskReview(taskId, decision, comment);
        if (approve) {
            jdbc.update("UPDATE agent_follow_up_reminder SET delivered_at = CURRENT_TIMESTAMP(6) WHERE pending_task_id = ?", taskId);
        }
        sessions.completeSession(task.getAgentSessionId(), List.of());
        return new Receipt(taskId, decision);
    }

    public record ReviewProgress(String status, boolean delivered) {}

    public ReviewProgress latestProgress(String customerId, String conversationId) {
        var rows = jdbc.query("""
                SELECT t.status, r.delivered_at FROM agent_session s
                JOIN agent_pending_task t ON t.agent_session_id = s.id
                LEFT JOIN agent_follow_up_reminder r ON r.pending_task_id = t.id
                WHERE s.customer_id = ? AND s.conversation_id = ?
                  AND CAST(s.customer_id AS BINARY(256)) = CAST(? AS BINARY(256))
                  AND CAST(t.customer_id AS BINARY(256)) = CAST(s.customer_id AS BINARY(256))
                  AND CAST(s.conversation_id AS BINARY(512)) = CAST(? AS BINARY(512))
                ORDER BY t.id DESC LIMIT 1
                """, (rs, row) -> new ReviewProgress(rs.getString(1), rs.getTimestamp(2) != null),
                customerId, conversationId, customerId, conversationId);
        return rows.isEmpty() ? new ReviewProgress("NONE", false) : rows.get(0);
    }

    public List<InboxMessage> inbox(String customerId, long beforeId) {
        return jdbc.query("""
                SELECT pending_task_id, body, delivered_at FROM agent_follow_up_reminder
                WHERE customer_id = ? AND delivered_at IS NOT NULL AND pending_task_id < ?
                  AND CAST(customer_id AS BINARY(256)) = CAST(? AS BINARY(256))
                ORDER BY pending_task_id DESC LIMIT 50
                """, (rs, row) -> new InboxMessage(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toLocalDateTime()),
                customerId, beforeId, customerId);
    }

    public ReviewProgress latestFollowUp(String customerId, String conversationId) {
        return jdbc.query("""
                SELECT t.status, r.delivered_at FROM agent_follow_up_reminder r
                JOIN agent_pending_task t ON t.id = r.pending_task_id
                WHERE r.customer_id = ? AND r.conversation_id = ? AND t.tool_name = ?
                  AND CAST(r.customer_id AS BINARY(256)) = CAST(? AS BINARY(256))
                  AND CAST(r.conversation_id AS BINARY(512)) = CAST(? AS BINARY(512))
                ORDER BY t.id DESC LIMIT 1
                """, (rs, row) -> new ReviewProgress(rs.getString(1), rs.getTimestamp(2) != null),
                customerId, conversationId, TOOL, customerId, conversationId)
                .stream().findFirst().orElse(new ReviewProgress("NONE", false));
    }
}
