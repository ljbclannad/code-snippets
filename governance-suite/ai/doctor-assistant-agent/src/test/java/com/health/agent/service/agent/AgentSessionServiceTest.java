package com.health.agent.service.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSON;
import com.health.agent.config.DoctorSafetyProperties;
import com.health.agent.mapper.AgentSessionMapper;
import com.health.agent.mapper.PendingTaskMapper;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentSession;
import com.health.agent.model.Message;
import com.health.agent.model.Step;
import com.lx.governance.agentplatform.model.PlatformContextVisibility;

@ExtendWith(MockitoExtension.class)
class AgentSessionServiceTest {

    @Mock
    private AgentSessionMapper sessionMapper;

    @Mock
    private PendingTaskMapper taskMapper;

    private AgentSessionService service;

    @BeforeEach
    void setUp() {
        service = new AgentSessionService();
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);

        DoctorSafetyProperties properties = new DoctorSafetyProperties();
        properties.getEvidenceRetention().setMaxRecords(2);
        properties.getEvidenceRetention().setMaxPayloadRecords(1);
        ReflectionTestUtils.setField(
                service,
                "contextEvidenceRetentionService",
                new ContextEvidenceRetentionService(properties));
    }

    @Test
    void shouldPersistRetainedContextEvidenceOnly() {
        AgentSession existing = AgentSession.builder()
                .id("1")
                .status("RUNNING")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(sessionMapper.selectById(1L)).thenReturn(existing);

        AgentContext context = AgentContext.initialize("C001", "D001", "目标");
        context.addEvidence("tool-1", "SRC", "summary-1", "payload-1", PlatformContextVisibility.AGENT_INTERNAL);
        context.addEvidence("tool-2", "SRC", "summary-2", "payload-2", PlatformContextVisibility.AUDIT_ONLY);
        context.addEvidence("tool-3", "SRC", "summary-3", "payload-3", PlatformContextVisibility.AGENT_INTERNAL);

        service.updateSession("1", List.of(Step.thought("t")), List.of(Message.user("m")), context, 2);

        ArgumentCaptor<AgentSession> captor = ArgumentCaptor.forClass(AgentSession.class);
        verify(sessionMapper).updateById(captor.capture());
        AgentSession updated = captor.getValue();
        AgentContext persisted = JSON.parseObject(updated.getContextJson(), AgentContext.class);
        assertEquals(2, persisted.getEvidenceRecords().size());
        assertEquals("tool-2", persisted.getEvidenceRecords().get(0).getToolName());
        assertNull(persisted.getEvidenceRecords().get(0).getPayload());
        assertEquals("tool-3", persisted.getEvidenceRecords().get(1).getToolName());
        assertEquals("payload-3", persisted.getEvidenceRecords().get(1).getPayload());
    }
}
