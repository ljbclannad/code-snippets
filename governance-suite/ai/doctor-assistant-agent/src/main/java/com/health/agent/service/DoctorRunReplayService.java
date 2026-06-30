package com.health.agent.service;

import java.util.LinkedHashMap;
import java.util.Map;

import com.health.agent.learning.model.AgentRunOutcome;
import com.health.agent.learning.model.AgentRunStrategySnapshot;
import com.health.agent.learning.service.AgentOutcomeRecorder;
import com.health.agent.learning.service.AgentRunStrategySnapshotRecorder;
import org.springframework.stereotype.Service;

import com.lx.governance.agentplatform.model.RunReplayView;
import com.lx.governance.agentplatform.runtime.RunReplayService;

/**
 * doctor 场景的 run 回放查询入口。
 */
@Service
public class DoctorRunReplayService {

    private final RunReplayService runReplayService;
    private final AgentRunStrategySnapshotRecorder strategySnapshotRecorder;
    private final AgentOutcomeRecorder outcomeRecorder;

    public DoctorRunReplayService(
            RunReplayService runReplayService,
            AgentRunStrategySnapshotRecorder strategySnapshotRecorder,
            AgentOutcomeRecorder outcomeRecorder) {
        this.runReplayService = runReplayService;
        this.strategySnapshotRecorder = strategySnapshotRecorder;
        this.outcomeRecorder = outcomeRecorder;
    }

    public RunReplayView getReplayByRunId(String runId) {
        RunReplayView replayView = runReplayService.replayByRunId(runId)
                .orElseThrow(() -> new IllegalArgumentException("未找到对应 run: " + runId));
        return replayView.toBuilder()
                .strategySnapshot(strategySnapshotRecorder.findByRunId(runId).map(this::snapshotToMap).orElse(null))
                .outcome(outcomeRecorder.findByRunId(runId).map(this::outcomeToMap).orElse(null))
                .build();
    }

    private Map<String, Object> snapshotToMap(AgentRunStrategySnapshot snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", snapshot.getRunId());
        payload.put("sessionId", snapshot.getSessionId());
        payload.put("scene", snapshot.getScene());
        payload.put("taskType", snapshot.getTaskType());
        payload.put("promptVersion", snapshot.getPromptVersion());
        payload.put("selectorVersion", snapshot.getSelectorVersion());
        payload.put("selectedAgentIds", snapshot.getSelectedAgentIds());
        payload.put("selectionMode", snapshot.getSelectionMode());
        payload.put("toolCatalogVersion", snapshot.getToolCatalogVersion());
        payload.put("toolSequence", snapshot.getToolSequence());
        payload.put("memoryProfile", snapshot.getMemoryProfile());
        payload.put("memoryRetrievalCount", snapshot.getMemoryRetrievalCount());
        payload.put("memoryRerankApplied", snapshot.getMemoryRerankApplied());
        payload.put("degradationLevel", snapshot.getDegradationLevel());
        payload.put("parallelEnabled", snapshot.getParallelEnabled());
        payload.put("fallbackAllowed", snapshot.getFallbackAllowed());
        payload.put("timeoutMillis", snapshot.getTimeoutMillis());
        payload.put("patientVisibleOnly", snapshot.getPatientVisibleOnly());
        return payload;
    }

    private Map<String, Object> outcomeToMap(AgentRunOutcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", outcome.getRunId());
        payload.put("sessionId", outcome.getSessionId());
        payload.put("scene", outcome.getScene());
        payload.put("taskType", outcome.getTaskType());
        payload.put("customerId", outcome.getCustomerId());
        payload.put("doctorId", outcome.getDoctorId());
        payload.put("finalStatus", outcome.getFinalStatus());
        payload.put("outcomeLabel", outcome.getOutcomeLabel() != null ? outcome.getOutcomeLabel().name() : null);
        payload.put("fallbackUsed", outcome.getFallbackUsed());
        payload.put("manualReviewRequired", outcome.getManualReviewRequired());
        payload.put("manualOverride", outcome.getManualOverride());
        payload.put("doctorAccepted", outcome.getDoctorAccepted());
        payload.put("doctorRejected", outcome.getDoctorRejected());
        payload.put("evidenceSufficient", outcome.getEvidenceSufficient());
        payload.put("toolErrorCount", outcome.getToolErrorCount());
        payload.put("retryCount", outcome.getRetryCount());
        payload.put("durationMs", outcome.getDurationMs());
        payload.put("errorCode", outcome.getErrorCode());
        payload.put("errorMessage", outcome.getErrorMessage());
        return payload;
    }
}
