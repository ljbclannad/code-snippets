package com.health.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.health.agent.config.DoctorSafetyProperties;
import com.health.agent.model.AgentContext;
import com.lx.governance.agentplatform.model.PlatformContextVisibility;

class ContextEvidenceRetentionServiceTest {

    @Test
    void shouldRetainLatestEvidenceAndDropOldPayloadsForPersistence() {
        DoctorSafetyProperties properties = new DoctorSafetyProperties();
        properties.getEvidenceRetention().setMaxRecords(3);
        properties.getEvidenceRetention().setMaxPayloadRecords(1);
        properties.getEvidenceRetention().setMaxSummaryChars(10);
        properties.getEvidenceRetention().setKeepAuditOnlyPayload(false);

        ContextEvidenceRetentionService service = new ContextEvidenceRetentionService(properties);
        AgentContext context = AgentContext.initialize("C001", "D001", "目标");
        context.addEvidence("tool-1", "SRC", "summary-1-very-long", "payload-1", PlatformContextVisibility.AGENT_INTERNAL);
        context.addEvidence("tool-2", "SRC", "summary-2-very-long", "payload-2", PlatformContextVisibility.AGENT_INTERNAL);
        context.addEvidence("tool-3", "SRC", "summary-3-very-long", "payload-3", PlatformContextVisibility.AUDIT_ONLY);
        context.addEvidence("tool-4", "SRC", "summary-4-very-long", "payload-4", PlatformContextVisibility.AGENT_INTERNAL);

        AgentContext retained = service.retainForPersistence(context);

        assertNotNull(retained);
        assertEquals(3, retained.getEvidenceRecords().size());
        assertEquals("tool-2", retained.getEvidenceRecords().get(0).getToolName());
        assertEquals("summary-2-...", retained.getEvidenceRecords().get(0).getSummary());
        assertNull(retained.getEvidenceRecords().get(0).getPayload());
        assertEquals("tool-3", retained.getEvidenceRecords().get(1).getToolName());
        assertNull(retained.getEvidenceRecords().get(1).getPayload());
        assertEquals("tool-4", retained.getEvidenceRecords().get(2).getToolName());
        assertEquals("payload-4", retained.getEvidenceRecords().get(2).getPayload());
    }
}
