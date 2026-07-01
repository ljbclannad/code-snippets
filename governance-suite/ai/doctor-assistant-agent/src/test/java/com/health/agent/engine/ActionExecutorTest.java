package com.health.agent.engine;

import com.health.agent.metrics.ToolMetrics;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.ActionResult;
import com.health.agent.model.PendingTask;
import com.health.agent.model.ToolExecutionOutput;
import com.health.agent.service.AgentAuditService;
import com.health.agent.service.PendingTaskService;
import com.health.agent.tool.Tool;
import com.health.agent.tool.ToolInputValidator;
import com.health.agent.tool.ToolRegistry;
import com.health.agent.tool.ToolRiskLevel;
import com.health.agent.service.PlatformToolOutputAdapter;
import com.lx.governance.agentplatform.model.AgentAccessContext;
import com.lx.governance.agentplatform.model.PlatformToolError;
import com.lx.governance.agentplatform.model.PlatformToolOutput;
import com.lx.governance.agentplatform.model.ToolDefinition;
import com.lx.governance.agentplatform.model.ToolIdempotencyPolicy;
import com.lx.governance.agentplatform.model.ToolRetryPolicy;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
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
        ReflectionTestUtils.setField(actionExecutor, "toolMetrics", new ToolMetrics(meterRegistry));
        ReflectionTestUtils.setField(actionExecutor, "toolInputValidator", new ToolInputValidator());
        ReflectionTestUtils.setField(actionExecutor, "platformToolOutputAdapter", new PlatformToolOutputAdapter());
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
        when(toolRegistry.getDefinition("draft_weight_management_plan")).thenReturn(ToolDefinition.builder()
            .toolName("draft_weight_management_plan")
            .riskLevel("HIGH")
            .auditEnabled(true)
            .build());

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
            eq("S001"),
            eq("C001"),
            eq("D001"),
            isNull(),
            eq("get_weight_history"),
            contains("\"customerId\":\"C001\""),
            isNull(),
            eq("LOW"),
            eq("INVALID_TOOL_INPUT")
        );
    }

    @Test
    void lowRiskActionShouldUseToolLevelTimeoutWhenConfigured() {
        Tool tool = Tool.builder()
            .name("get_patient_profile")
            .description("查询患者档案")
            .riskLevel(ToolRiskLevel.LOW)
            .inputSchema(Map.of("customerId", Map.of("type", "string", "required", true)))
            .executor(input -> {
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "ok";
            })
            .build();
        when(toolRegistry.getTool("get_patient_profile")).thenReturn(tool);
        when(toolRegistry.getDefinition("get_patient_profile")).thenReturn(ToolDefinition.builder()
            .toolName("get_patient_profile")
            .riskLevel("LOW")
            .timeoutMs(50L)
            .retryPolicy(ToolRetryPolicy.noRetry())
            .auditEnabled(true)
            .build());
        ReflectionTestUtils.setField(actionExecutor, "toolTimeoutMillis", 500L);

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
    }

    @Test
    void lowRiskActionShouldWrapRawToolOutputIntoStandardExecutionOutput() {
        Tool tool = Tool.builder()
            .name("get_patient_profile")
            .description("查询患者档案")
            .riskLevel(ToolRiskLevel.LOW)
            .inputSchema(Map.of("customerId", Map.of("type", "string", "required", true)))
            .executor(input -> Map.of("customerId", "C001", "name", "张三"))
            .build();
        when(toolRegistry.getTool("get_patient_profile")).thenReturn(tool);

        ActionDecision action = ActionDecision.builder()
            .toolName("get_patient_profile")
            .input(Map.of("customerId", "C001"))
            .reason("获取患者档案")
            .build();

        ActionResult result = actionExecutor.execute(action, "C001", "D001", "S001");

        assertTrue(result.isSuccess());
        PlatformToolOutput output = assertInstanceOf(PlatformToolOutput.class, result.getOutput());
        assertEquals("查询患者档案", output.getSummary());
        assertTrue(output.getStateUpdates().containsKey("patient_profile"));
        assertFalse(output.getEvidences().isEmpty());
    }

    @Test
    void executeShouldRejectToolWhenAccessScopeDoesNotMatch() {
        Tool tool = Tool.builder()
            .name("send_health_report")
            .description("发送报告")
            .riskLevel(ToolRiskLevel.MEDIUM)
            .inputSchema(Map.of(
                "customerId", Map.of("type", "string", "required", true),
                "reportContent", Map.of("type", "string", "required", true)
            ))
            .executor(input -> "ok")
            .build();
        when(toolRegistry.getTool("send_health_report")).thenReturn(tool);
        when(toolRegistry.getDefinition("send_health_report")).thenReturn(ToolDefinition.builder()
            .toolName("send_health_report")
            .riskLevel("MEDIUM")
            .visibleGroupIds(java.util.List.of("vip"))
            .auditEnabled(true)
            .build());

        ActionResult result = actionExecutor.execute(
            ActionDecision.builder()
                .toolName("send_health_report")
                .input(Map.of("customerId", "C001", "reportContent", "report"))
                .reason("权限测试")
                .build(),
            "C001",
            "D001",
            "S001",
            AgentAccessContext.builder().groupId("standard").build()
        );

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("无权限"));
        PlatformToolError error = assertInstanceOf(PlatformToolError.class, result.getOutput());
        assertEquals("TOOL_ACCESS_DENIED", error.getCode());
        verify(agentAuditService).recordToolExecution(
            eq("S001"),
            eq("C001"),
            eq("D001"),
            isNull(),
            eq("send_health_report"),
            contains("\"customerId\":\"C001\""),
            isNull(),
            eq("MEDIUM"),
            eq("TOOL_ACCESS_DENIED")
        );
    }

    @Test
    void executeShouldReplayCachedOutputForIdempotentTool() {
        AtomicInteger calls = new AtomicInteger();
        Tool tool = Tool.builder()
            .name("get_patient_profile")
            .description("查询患者档案")
            .riskLevel(ToolRiskLevel.LOW)
            .inputSchema(Map.of("customerId", Map.of("type", "string", "required", true)))
            .executor(input -> {
                calls.incrementAndGet();
                return Map.of("customerId", "C001", "name", "张三");
            })
            .build();
        when(toolRegistry.getTool("get_patient_profile")).thenReturn(tool);
        when(toolRegistry.getDefinition("get_patient_profile")).thenReturn(ToolDefinition.builder()
            .toolName("get_patient_profile")
            .riskLevel("LOW")
            .idempotencyPolicy(ToolIdempotencyPolicy.IDEMPOTENT)
            .auditEnabled(true)
            .build());

        ActionDecision action = ActionDecision.builder()
            .toolName("get_patient_profile")
            .input(Map.of("customerId", "C001"))
            .reason("幂等测试")
            .build();

        ActionResult first = actionExecutor.execute(action, "C001", "D001", "S001");
        ActionResult second = actionExecutor.execute(action, "C001", "D001", "S001");

        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertEquals(1, calls.get());
        verify(agentAuditService, times(2)).recordToolExecution(
            eq("S001"),
            eq("C001"),
            eq("D001"),
            isNull(),
            eq("get_patient_profile"),
            contains("\"customerId\":\"C001\""),
            org.mockito.ArgumentMatchers.any(),
            eq("LOW"),
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void executeShouldOpenCircuitAfterConfiguredFailures() {
        Tool tool = Tool.builder()
            .name("send_reminder")
            .description("发送提醒")
            .riskLevel(ToolRiskLevel.MEDIUM)
            .inputSchema(Map.of(
                "customerId", Map.of("type", "string", "required", true),
                "message", Map.of("type", "string", "required", true)
            ))
            .executor(input -> {
                throw new IllegalStateException("boom");
            })
            .build();
        when(toolRegistry.getTool("send_reminder")).thenReturn(tool);
        when(toolRegistry.getDefinition("send_reminder")).thenReturn(ToolDefinition.builder()
            .toolName("send_reminder")
            .riskLevel("MEDIUM")
            .retryPolicy(ToolRetryPolicy.noRetry())
            .circuitBreakerFailureThreshold(1)
            .circuitOpenMillis(60_000L)
            .auditEnabled(true)
            .build());

        ActionDecision action = ActionDecision.builder()
            .toolName("send_reminder")
            .input(Map.of("customerId", "C001", "message", "hello"))
            .reason("熔断测试")
            .build();

        ActionResult first = actionExecutor.execute(action, "C001", "D001", "S001");
        ActionResult second = actionExecutor.execute(action, "C001", "D001", "S001");

        assertFalse(first.isSuccess());
        assertFalse(second.isSuccess());
        assertTrue(second.getErrorMessage().contains("TOOL_CIRCUIT_OPEN"));
        PlatformToolError error = assertInstanceOf(PlatformToolError.class, second.getOutput());
        assertEquals("TOOL_CIRCUIT_OPEN", error.getCode());
    }
}
