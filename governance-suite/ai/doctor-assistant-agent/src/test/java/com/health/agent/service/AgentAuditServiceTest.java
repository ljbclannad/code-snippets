package com.health.agent.service;

import com.health.agent.mapper.AgentExecutionLogMapper;
import com.health.agent.model.AgentExecutionLog;
import com.health.agent.model.AgentResult;
import com.health.agent.model.PendingTask;
import com.health.agent.model.ResultStatus;
import com.health.agent.model.Step;
import com.health.agent.model.StructuredFinalAnswer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentAuditServiceTest {

    @Mock
    private AgentExecutionLogMapper executionLogMapper;

    private AgentAuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AgentAuditService();
        ReflectionTestUtils.setField(auditService, "executionLogMapper", executionLogMapper);
    }

    @Test
    void shouldPersistHighRiskPendingTaskAuditLog() {
        PendingTask task = PendingTask.builder()
            .id(101L)
            .agentSessionId("S001")
            .customerId("C001")
            .doctorId("D001")
            .toolName("draft_weight_management_plan")
            .toolDescription("起草减重管理方案建议")
            .inputJson("{\"customerId\":\"C001\"}")
            .reason("需要医生审批")
            .status("PENDING")
            .build();

        auditService.recordPendingTaskCreated(task, "减重方案评估");

        ArgumentCaptor<AgentExecutionLog> captor = ArgumentCaptor.forClass(AgentExecutionLog.class);
        verify(executionLogMapper).insert(captor.capture());

        AgentExecutionLog log = captor.getValue();
        assertEquals("S001", log.getSessionId());
        assertEquals("draft_weight_management_plan", log.getToolName());
        assertEquals("PENDING", log.getApprovalStatus());
        assertTrue(log.getToolInputJson().contains("customerId"));
    }

    @Test
    void shouldUpdateAuditStatusWhenTaskApproved() {
        AgentExecutionLog existing = new AgentExecutionLog();
        existing.setId(88L);
        existing.setPendingTaskId(101L);
        existing.setApprovalStatus("PENDING");

        when(executionLogMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(existing));

        auditService.recordTaskReview(101L, "APPROVED", "处方合理");

        ArgumentCaptor<AgentExecutionLog> captor = ArgumentCaptor.forClass(AgentExecutionLog.class);
        verify(executionLogMapper).updateById(captor.capture());

        AgentExecutionLog updated = captor.getValue();
        assertEquals("APPROVED", updated.getApprovalStatus());
        assertEquals("处方合理", updated.getReviewComment());
    }

    @Test
    void shouldPersistMediumRiskToolExecutionAudit() {
        auditService.recordToolExecution(
            "S001",
            "C001",
            "D001",
            "减重随访",
            "send_reminder",
            "{\"message\":\"请记录晚餐\"}",
            "{\"success\":true}",
            "MEDIUM",
            "SUCCESS"
        );

        ArgumentCaptor<AgentExecutionLog> captor = ArgumentCaptor.forClass(AgentExecutionLog.class);
        verify(executionLogMapper).insert(captor.capture());

        AgentExecutionLog log = captor.getValue();
        assertEquals("send_reminder", log.getToolName());
        assertEquals("MEDIUM", log.getApprovalStatus());
        assertTrue(log.getToolOutputJson().contains("success"));
    }

    @Test
    void shouldPersistFinalAgentResultAudit() {
        StructuredFinalAnswer finalAnswer = StructuredFinalAnswer.builder()
            .summary("患者近30天减重稳定。")
            .riskLevel("LOW")
            .build();
        AgentResult result = AgentResult.success("S001", finalAnswer, List.of(Step.thought("done")), 3);

        auditService.recordSessionResult(
            "S001",
            "C001",
            "D001",
            "减重随访",
            result,
            3,
            520L
        );

        ArgumentCaptor<AgentExecutionLog> captor = ArgumentCaptor.forClass(AgentExecutionLog.class);
        verify(executionLogMapper).insert(captor.capture());

        AgentExecutionLog log = captor.getValue();
        assertEquals(ResultStatus.SUCCESS.name(), log.getResultStatus());
        assertTrue(log.getFinalAnswer().contains("问题摘要"));
        assertEquals(3, log.getTotalSteps());
    }

    @Test
    void shouldPersistMedicalSafetyBlockAudit() {
        auditService.recordSafetyBlock(
            "S001",
            "C001",
            "D001",
            "患者想调整减重药物",
            "MEDICATION_ADJUSTMENT",
            "不得自动生成药物调整结论",
            "{\"goal\":\"患者想调整减重药物\"}"
        );

        ArgumentCaptor<AgentExecutionLog> captor = ArgumentCaptor.forClass(AgentExecutionLog.class);
        verify(executionLogMapper).insert(captor.capture());

        AgentExecutionLog log = captor.getValue();
        assertEquals("SAFETY_BLOCK", log.getResultStatus());
        assertEquals("medical_safety_rule", log.getToolName());
        assertEquals("MEDICATION_ADJUSTMENT", log.getApprovalStatus());
        assertTrue(log.getErrorMessage().contains("不得自动生成药物调整结论"));
    }

    @Test
    void shouldPersistResumeContextUpdateAudit() {
        auditService.recordResumeContextUpdate(
            "S001",
            "C001",
            "D001",
            "恢复执行",
            "get_weight_history",
            Map.of("current_sub_agent", "DataCollectionAgent"),
            Map.of(
                "current_sub_agent", "WeightAnalysisAgent",
                "weight_history", List.of(Map.of("date", "2026-06-01", "weight", 68.5))
            )
        );

        ArgumentCaptor<AgentExecutionLog> captor = ArgumentCaptor.forClass(AgentExecutionLog.class);
        verify(executionLogMapper).insert(captor.capture());

        AgentExecutionLog log = captor.getValue();
        assertEquals("RESUME_CONTEXT_UPDATED", log.getResultStatus());
        assertEquals("get_weight_history", log.getToolName());
        assertTrue(log.getToolInputJson().contains("current_sub_agent"));
        assertTrue(log.getToolOutputJson().contains("weight_history"));
        assertTrue(log.getErrorMessage().contains("added=[weight_history]"));
    }
}
