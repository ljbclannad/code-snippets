package com.health.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.health.agent.config.DoctorSafetyProperties;
import com.health.agent.model.AgentContext;
import com.health.agent.model.ContextEntry;
import com.health.agent.model.EvidenceRecord;
import com.lx.governance.agentplatform.model.PlatformContextVisibility;

/**
 * 控制持久化到上下文中的证据链保留规模。
 */
@Service
public class ContextEvidenceRetentionService {

    private final DoctorSafetyProperties safetyProperties;

    public ContextEvidenceRetentionService(DoctorSafetyProperties safetyProperties) {
        this.safetyProperties = safetyProperties != null ? safetyProperties : new DoctorSafetyProperties();
    }

    public AgentContext retainForPersistence(AgentContext context) {
        if (context == null || !isEnabled()) {
            return context;
        }
        AgentContext retained = AgentContext.initialize(
                context.getCustomerId(),
                context.getDoctorId(),
                context.getGoal());
        if (context.getSlots() != null) {
            for (Map.Entry<String, ContextEntry> entry : context.getSlots().entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                retained.putSlot(
                        entry.getKey(),
                        entry.getValue().getValue(),
                        entry.getValue().getSource(),
                        entry.getValue().getVisibility());
            }
        }

        List<EvidenceRecord> original = context.getEvidenceRecords() != null
                ? context.getEvidenceRecords()
                : List.of();
        int maxRecords = Math.max(1, safetyProperties.getEvidenceRetention().getMaxRecords());
        int maxPayloadRecords = Math.max(0, safetyProperties.getEvidenceRetention().getMaxPayloadRecords());
        int startIndex = Math.max(0, original.size() - maxRecords);
        List<EvidenceRecord> window = new ArrayList<>(original.subList(startIndex, original.size()));
        int payloadStartIndex = Math.max(0, window.size() - maxPayloadRecords);
        for (int i = 0; i < window.size(); i++) {
            EvidenceRecord record = window.get(i);
            if (record == null) {
                continue;
            }
            boolean keepPayload = i >= payloadStartIndex
                    && (record.getVisibility() != PlatformContextVisibility.AUDIT_ONLY
                    || safetyProperties.getEvidenceRetention().isKeepAuditOnlyPayload());
            retained.addEvidence(
                    record.getToolName(),
                    record.getSource(),
                    abbreviate(record.getSummary()),
                    keepPayload ? record.getPayload() : null,
                    record.getVisibility());
        }
        return retained;
    }

    private boolean isEnabled() {
        return safetyProperties.getEvidenceRetention() != null
                && safetyProperties.getEvidenceRetention().isEnabled();
    }

    private String abbreviate(String summary) {
        if (summary == null) {
            return null;
        }
        int maxSummaryChars = Math.max(1, safetyProperties.getEvidenceRetention().getMaxSummaryChars());
        if (summary.length() <= maxSummaryChars) {
            return summary;
        }
        return summary.substring(0, maxSummaryChars) + "...";
    }
}
