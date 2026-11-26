package com.health.agent.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.health.agent.model.AgentSession;
import com.health.agent.model.PendingTask;
import com.health.agent.model.SessionStatus;
import com.health.agent.model.TaskStatus;
import com.health.agent.config.DoctorAgentPlatformProperties;
import com.lx.governance.agentplatform.model.ApprovalStatus;
import com.lx.governance.agentplatform.model.PromptRenderRequest;
import com.lx.governance.agentplatform.model.PromptTemplateDescriptor;
import com.lx.governance.agentplatform.model.PromptTemplateType;
import com.lx.governance.agentplatform.model.PlatformAuditEventType;
import com.lx.governance.agentplatform.model.PlatformOutcomeMode;
import com.lx.governance.agentplatform.model.RunStatus;
import com.lx.governance.agentplatform.runtime.InMemoryPromptRegistry;
import com.lx.governance.agentplatform.runtime.InMemoryRunPersistenceRepository;
import com.lx.governance.agentplatform.runtime.PlatformMetricsService;
import com.lx.governance.agentplatform.runtime.PromptTemplateRenderer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DoctorPlatformRunPersistenceServiceTest {

    private DoctorAgentPlatformProperties defaultPlatformProperties() {
        return new DoctorAgentPlatformProperties();
    }

    @Test
    void shouldPersistSnapshotAndConversationLinkForPatientTaskRun() {
        InMemoryRunPersistenceRepository repository = new InMemoryRunPersistenceRepository();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DoctorPlatformRunPersistenceService service = new DoctorPlatformRunPersistenceService(
                new AgentSessionPlatformSnapshotMapper(),
                repository,
                repository,
                repository,
                new PlatformMetricsService(meterRegistry),
                new InMemoryPromptRegistry(),
                defaultPlatformProperties());

        AgentSession session = AgentSession.builder()
                .id("S001")
                .customerId("C001")
                .doctorId("D001")
                .goal("患者随访分析")
                .status(SessionStatus.PAUSED.name())
                .currentStep(2)
                .createdAt(LocalDateTime.of(2026, 6, 18, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 18, 10, 5))
                .build();
        PendingTask pendingTask = PendingTask.builder()
                .id(101L)
                .agentSessionId("S001")
                .doctorId("D001")
                .toolName("patient_follow_up_review")
                .status(TaskStatus.PENDING.name())
                .createdAt(LocalDateTime.of(2026, 6, 18, 10, 4))
                .build();

        service.persistPatientTaskRun(
                session,
                List.of(pendingTask),
                "conv-1",
                PlatformOutcomeMode.PENDING_APPROVAL,
                "PATIENT_TASK_TEMPLATE_FALLBACK",
                "AGENT_EXCEPTION");

        assertTrue(repository.findSnapshotByRunId("S001").isPresent());
        assertEquals(RunStatus.WAITING_APPROVAL,
                repository.findSnapshotByRunId("S001").orElseThrow().getRun().getStatus());
        assertEquals("conv-1",
                repository.findLatestByConversationId("conv-1").orElseThrow().getConversationId());
        assertEquals(PlatformOutcomeMode.PENDING_APPROVAL,
                repository.findLatestByConversationId("conv-1").orElseThrow().getOutcomeMode());
        assertEquals(1, repository.findAuditEventsByRunId("S001").size());
        assertEquals(PlatformAuditEventType.RUN_PERSISTED,
                repository.findAuditEventsByRunId("S001").get(0).getEventType());
        assertEquals(1.0, meterRegistry.get("platform.run.total")
                .tag("agent_id", "doctor-assistant-agent")
                .counter().count());
        assertEquals(1.0, meterRegistry.get("platform.run.fallback")
                .tag("reason", "AGENT_EXCEPTION")
                .counter().count());
    }

    @Test
    void shouldSyncApprovalStatusIntoPlatformSnapshot() {
        InMemoryRunPersistenceRepository repository = new InMemoryRunPersistenceRepository();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DoctorPlatformRunPersistenceService service = new DoctorPlatformRunPersistenceService(
                new AgentSessionPlatformSnapshotMapper(),
                repository,
                repository,
                repository,
                new PlatformMetricsService(meterRegistry),
                new InMemoryPromptRegistry(),
                defaultPlatformProperties());

        AgentSession session = AgentSession.builder()
                .id("S002")
                .customerId("C002")
                .doctorId("D002")
                .goal("患者随访分析")
                .status(SessionStatus.PAUSED.name())
                .currentStep(2)
                .createdAt(LocalDateTime.of(2026, 6, 18, 11, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 18, 11, 5))
                .build();
        PendingTask pendingTask = PendingTask.builder()
                .id(202L)
                .agentSessionId("S002")
                .doctorId("D002")
                .toolName("patient_follow_up_review")
                .status(TaskStatus.PENDING.name())
                .createdAt(LocalDateTime.of(2026, 6, 18, 11, 4))
                .build();
        service.persistPatientTaskRun(
                session,
                List.of(pendingTask),
                "conv-2",
                PlatformOutcomeMode.PENDING_APPROVAL,
                "PATIENT_TASK_TEMPLATE_FALLBACK",
                null);

        pendingTask.setStatus(TaskStatus.APPROVED.name());
        service.syncApprovalStatus(pendingTask);

        assertEquals(
                ApprovalStatus.APPROVED,
                repository.findSnapshotByRunId("S002").orElseThrow().getApprovalTasks().get(0).getStatus());
        assertEquals(2, repository.findAuditEventsByRunId("S002").size());
        assertEquals(PlatformAuditEventType.APPROVAL_STATUS_SYNCED,
                repository.findAuditEventsByRunId("S002").get(1).getEventType());
        assertEquals(1.0, meterRegistry.get("platform.run.approval.required")
                .tag("agent_id", "doctor-assistant-agent")
                .counter().count());
    }

    @Test
    void shouldUseActivePromptVersionInAuditEvent() {
        InMemoryRunPersistenceRepository repository = new InMemoryRunPersistenceRepository();
        InMemoryPromptRegistry promptRegistry = new InMemoryPromptRegistry(List.of(new PromptTemplateRenderer() {
            @Override
            public PromptTemplateDescriptor descriptor() {
                return PromptTemplateDescriptor.builder()
                        .scene(com.health.agent.engine.ReActPromptBuilder.DOCTOR_SCENE)
                        .agentId(com.health.agent.engine.ReActPromptBuilder.DOCTOR_AGENT)
                        .version("doctor-react-prompt-v2")
                        .type(PromptTemplateType.SYSTEM)
                        .description("test prompt")
                        .build();
            }

            @Override
            public String render(PromptRenderRequest request) {
                return "test";
            }
        }));
        DoctorPlatformRunPersistenceService service = new DoctorPlatformRunPersistenceService(
                new AgentSessionPlatformSnapshotMapper(),
                repository,
                repository,
                repository,
                new PlatformMetricsService(new SimpleMeterRegistry()),
                promptRegistry,
                defaultPlatformProperties());

        AgentSession session = AgentSession.builder()
                .id("S003")
                .customerId("C003")
                .doctorId("D003")
                .goal("患者随访分析")
                .status(SessionStatus.RUNNING.name())
                .createdAt(LocalDateTime.of(2026, 6, 18, 12, 0))
                .updatedAt(LocalDateTime.of(2026, 6, 18, 12, 3))
                .build();

        service.persistPatientTaskRun(
                session,
                List.of(),
                "conv-3",
                PlatformOutcomeMode.AGENT_RESULT,
                "AGENT_COMPLETED",
                null);

        assertEquals("doctor-react-prompt-v2",
                repository.findAuditEventsByRunId("S003").get(0).getPromptVersion());
    }
}
