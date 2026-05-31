package com.health.agent.engine;

import com.health.agent.metrics.ToolMetrics;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.ActionResult;
import com.health.agent.model.PendingTask;
import com.health.agent.service.AgentAuditService;
import com.health.agent.service.PendingTaskService;
import com.health.agent.tool.Tool;
import com.health.agent.tool.ToolInputValidator;
import com.health.agent.tool.ToolRegistry;
import com.health.agent.tool.ToolRiskLevel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionExecutorTest {

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private PendingTaskService pendingTaskService;

    @Mock
    private AgentAuditService agentAuditService;

    private ActionExecutor actionExecutor;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        actionExecutor = new ActionExecutor();
        ReflectionTestUtils.setField(actionExecutor, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(actionExecutor, "pendingTaskService", pendingTaskService);
        ReflectionTestUtils.setField(actionExecutor, "agentAuditService", agentAuditService);
        meterRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(
            actionExecutor,
            "toolMetrics",
            new ToolMetrics(meterRegistry)
        );
        ReflectionTestUtils.setField(actionExecutor, "toolInputValidator", new ToolInputValidator());
        ReflectionTestUtils.setField(actionExecutor, "toolTimeoutMillis", 100L);
        ReflectionTestUtils.setField(actionExecutor, "toolExecutorCorePoolSize", 1);
        ReflectionTestUtils.setField(actionExecutor, "toolExecutorMaxPoolSize", 1);
        ReflectionTestUtils.setField(actionExecutor, "toolExecutorQueueCapacity", 1);
        ReflectionTestUtils.setField(actionExecutor, "toolExecutorKeepAliveMillis", 1000L);
    }

    @Test
    void highRiskActionShouldPersistDoctorIdOnPendingTask() {
        Tool tool = Tool.builder()
            .name("draft_weight_management_plan")
            .description("起草减重管理方案建议")
            .riskLevel(ToolRiskLevel.HIGH)
            .build();
        when(toolRegistry.getTool("draft_weight_management_plan")).thenReturn(tool);

        ActionDecision action = ActionDecision.builder()
            .toolName("draft_weight_management_plan")
            .input(Map.of("customerId", "C001", "recommendations", new String[]{"A"}))
            .reason("需要医生审批")
            .build();

        ActionResult result = actionExecutor.execute(action, "C001", "D001", "S001");

        assertTrue(result.isNeedsApproval());
        ArgumentCaptor<PendingTask> taskCaptor = ArgumentCaptor.forClass(PendingTask.class);
        verify(pendingTaskService).save(taskCaptor.capture());

        PendingTask savedTask = taskCaptor.getValue();
        assertNotNull(savedTask.getDoctorId());
        assertEquals("D001", savedTask.getDoctorId());
        assertEquals("draft_weight_management_plan", savedTask.getToolName());
        assertEquals("S001", savedTask.getAgentSessionId());
        assertEquals("C001", savedTask.getCustomerId());
        verify(agentAuditService).recordPendingTaskCreated(savedTask, null);
    }

    @Test
    void lowRiskActionShouldRejectUnexpectedFieldAndInvalidType() {
        Tool tool = Tool.builder()
            .name("get_weight_history")
            .description("查询体重历史")
            .riskLevel(ToolRiskLevel.LOW)
            .inputSchema(Map.of(
                "customerId", Map.of("type", "string", "required", true),
                "days", Map.of("type", "integer", "required", false, "min", 1, "max", 90)
            ))
            .executor(input -> "ok")
            .build();
        when(toolRegistry.getTool("get_weight_history")).thenReturn(tool);

        ActionDecision action = ActionDecision.builder()
            .toolName("get_weight_history")
            .input(Map.of("customerId", "C001", "days", "bad", "extra", "field"))
            .reason("测试非法输入")
            .build();

        ActionResult result = actionExecutor.execute(action, "C001", "D001", "S001");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("INVALID_TOOL_INPUT"));
        verify(agentAuditService).recordToolExecution(
            org.mockito.ArgumentMatchers.eq("S001"),
            org.mockito.ArgumentMatchers.eq("C001"),
            org.mockito.ArgumentMatchers.eq("D001"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("get_weight_history"),
            contains("\"customerId\":\"C001\""),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("LOW"),
            org.mockito.ArgumentMatchers.eq("INVALID_TOOL_INPUT")
        );
    }

    @Test
    void lowRiskActionShouldReturnTimeoutErrorWhenToolRunsTooLong() {
        Tool tool = Tool.builder()
            .name("get_patient_profile")
            .description("查询患者档案")
            .riskLevel(ToolRiskLevel.LOW)
            .inputSchema(Map.of(
                "customerId", Map.of("type", "string", "required", true)
            ))
            .executor(input -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "ok";
            })
            .build();
        when(toolRegistry.getTool("get_patient_profile")).thenReturn(tool);

        ActionDecision action = ActionDecision.builder()
            .toolName("get_patient_profile")
            .input(Map.of("customerId", "C001"))
            .reason("测试超时")
            .build();

        ActionResult result = actionExecutor.execute(action, "C001", "D001", "S001");

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("TIMEOUT"));
        Timer failureTimer = meterRegistry.find("tool.execution.duration")
            .tag("tool", "get_patient_profile")
            .tag("outcome", "failure")
            .timer();
        assertNotNull(failureTimer);
        assertEquals(1, failureTimer.count());
        assertNotNull(
            meterRegistry.find("tool.executor.queue.size")
                .tag("executor", "agent-tool-executor")
                .gauge()
        );
        verify(agentAuditService).recordToolExecution(
            "S001",
            "C001",
            "D001",
            null,
            "get_patient_profile",
            "{\"customerId\":\"C001\"}",
            null,
            "LOW",
            "ERROR"
        );
    }
}
