package com.lx.governance.agentplatform.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ApprovalModelTest {

    @Test
    void shouldBuildApprovalRequestWithSlaPolicy() {
        ApprovalRequest request = ApprovalRequest.builder()
                .approvalTaskId("ap-1")
                .runId("run-1")
                .workflowId("wf-1")
                .title("医生确认高风险工具调用")
                .description("需要确认是否继续执行处方调整")
                .reasonCode(ApprovalReasonCode.HIGH_RISK_TOOL_CALL)
                .reasonDetail("create_follow_up_task 触发审批")
                .assigneeId("D001")
                .status(ApprovalStatus.PENDING)
                .payload(Map.of("toolName", "create_follow_up_task"))
                .slaPolicy(ApprovalSlaPolicy.builder()
                        .dueInMinutes(30)
                        .expireInMinutes(60)
                        .timeoutDecisionType(ApprovalDecisionType.EXPIRED)
                        .reminderPolicy(ApprovalReminderPolicy.builder()
                                .firstReminderAfterMinutes(10)
                                .repeatReminderEveryMinutes(10)
                                .maxReminderCount(3)
                                .build())
                        .build())
                .createdAt(1000L)
                .expireAt(4600L)
                .build();

        assertEquals(ApprovalReasonCode.HIGH_RISK_TOOL_CALL, request.getReasonCode());
        assertEquals(30, request.getSlaPolicy().getDueInMinutes());
        assertEquals(3, request.getSlaPolicy().getReminderPolicy().getMaxReminderCount());
    }
}
