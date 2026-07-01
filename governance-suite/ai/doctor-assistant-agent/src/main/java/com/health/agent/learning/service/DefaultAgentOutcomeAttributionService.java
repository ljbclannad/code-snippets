package com.health.agent.learning.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.health.agent.learning.entity.AgentOutcomeAttributionEntity;
import com.health.agent.learning.enums.AgentOutcomeLabel;
import com.health.agent.learning.enums.AgentPrimaryCause;
import com.health.agent.learning.mapper.AgentOutcomeAttributionMapper;
import com.health.agent.learning.model.AgentOutcomeAttribution;
import com.health.agent.learning.model.AgentRunOutcome;
import com.health.agent.learning.model.AgentRunStrategySnapshot;

@Service
public class DefaultAgentOutcomeAttributionService implements AgentOutcomeAttributionService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AgentOutcomeAttributionMapper mapper;
    private final AgentOutcomeRecorder outcomeRecorder;
    private final AgentRunStrategySnapshotRecorder strategySnapshotRecorder;

    public DefaultAgentOutcomeAttributionService(
            AgentOutcomeAttributionMapper mapper,
            AgentOutcomeRecorder outcomeRecorder,
            AgentRunStrategySnapshotRecorder strategySnapshotRecorder) {
        this.mapper = mapper;
        this.outcomeRecorder = outcomeRecorder;
        this.strategySnapshotRecorder = strategySnapshotRecorder;
    }

    @Override
    public void createOrUpdate(AgentOutcomeAttribution attribution) {
        if (attribution == null || isBlank(attribution.getRunId())) {
            return;
        }
        AgentOutcomeAttributionEntity existing = selectByRunId(attribution.getRunId()).orElse(null);
        if (existing == null) {
            AgentOutcomeAttributionEntity entity = toEntity(attribution);
            LocalDateTime now = LocalDateTime.now();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            mapper.insert(entity);
            return;
        }
        merge(existing, attribution);
        existing.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(existing);
    }

    @Override
    public Optional<AgentOutcomeAttribution> findByRunId(String runId) {
        return selectByRunId(runId).map(this::toModel);
    }

    @Override
    public Optional<AgentOutcomeAttribution> recompute(String runId) {
        if (isBlank(runId) || outcomeRecorder == null) {
            return Optional.empty();
        }
        AgentRunOutcome outcome = outcomeRecorder.findByRunId(runId).orElse(null);
        if (outcome == null) {
            return Optional.empty();
        }
        AgentRunStrategySnapshot snapshot = strategySnapshotRecorder != null
                ? strategySnapshotRecorder.findByRunId(runId).orElse(null)
                : null;
        AgentOutcomeAttribution attribution = resolveAttribution(outcome, snapshot);
        createOrUpdate(attribution);
        return Optional.of(attribution);
    }

    private Optional<AgentOutcomeAttributionEntity> selectByRunId(String runId) {
        if (isBlank(runId)) {
            return Optional.empty();
        }
        LambdaQueryWrapper<AgentOutcomeAttributionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentOutcomeAttributionEntity::getRunId, runId).last("LIMIT 1");
        return Optional.ofNullable(mapper.selectOne(wrapper));
    }

    private AgentOutcomeAttributionEntity toEntity(AgentOutcomeAttribution attribution) {
        AgentOutcomeAttributionEntity entity = new AgentOutcomeAttributionEntity();
        merge(entity, attribution);
        return entity;
    }

    private void merge(AgentOutcomeAttributionEntity entity, AgentOutcomeAttribution attribution) {
        entity.setRunId(valueOr(entity.getRunId(), attribution.getRunId()));
        entity.setSessionId(valueOr(entity.getSessionId(), attribution.getSessionId()));
        entity.setScene(valueOr(entity.getScene(), attribution.getScene()));
        entity.setTaskType(valueOr(entity.getTaskType(), attribution.getTaskType()));
        entity.setPrimaryCause(attribution.getPrimaryCause() != null
                ? attribution.getPrimaryCause().name()
                : entity.getPrimaryCause());
        entity.setSecondaryCausesJson(jsonOr(entity.getSecondaryCausesJson(), attribution.getSecondaryCauses()));
        entity.setConfidence(valueOr(entity.getConfidence(), attribution.getConfidence()));
        entity.setEvidenceSummaryJson(jsonOr(entity.getEvidenceSummaryJson(), attribution.getEvidenceSummary()));
    }

    private AgentOutcomeAttribution toModel(AgentOutcomeAttributionEntity entity) {
        return AgentOutcomeAttribution.builder()
                .runId(entity.getRunId())
                .sessionId(entity.getSessionId())
                .scene(entity.getScene())
                .taskType(entity.getTaskType())
                .primaryCause(entity.getPrimaryCause() != null ? AgentPrimaryCause.valueOf(entity.getPrimaryCause()) : null)
                .secondaryCauses(parseStringList(entity.getSecondaryCausesJson()))
                .confidence(entity.getConfidence())
                .evidenceSummary(parseMap(entity.getEvidenceSummaryJson()))
                .build();
    }

    private AgentOutcomeAttribution resolveAttribution(AgentRunOutcome outcome, AgentRunStrategySnapshot snapshot) {
        AgentPrimaryCause primaryCause = resolvePrimaryCause(outcome, snapshot);
        List<String> secondaryCauses = resolveSecondaryCauses(primaryCause, outcome, snapshot);
        return AgentOutcomeAttribution.builder()
                .runId(outcome.getRunId())
                .sessionId(outcome.getSessionId())
                .scene(outcome.getScene() != null ? outcome.getScene() : snapshot != null ? snapshot.getScene() : null)
                .taskType(outcome.getTaskType() != null ? outcome.getTaskType() : snapshot != null ? snapshot.getTaskType() : null)
                .primaryCause(primaryCause)
                .secondaryCauses(secondaryCauses)
                .confidence(resolveConfidence(primaryCause, outcome, snapshot))
                .evidenceSummary(buildEvidenceSummary(outcome, snapshot, primaryCause, secondaryCauses))
                .build();
    }

    private AgentPrimaryCause resolvePrimaryCause(AgentRunOutcome outcome, AgentRunStrategySnapshot snapshot) {
        if (outcome == null) {
            return AgentPrimaryCause.UNKNOWN;
        }
        String errorCode = safeLower(outcome.getErrorCode());
        String errorMessage = safeLower(outcome.getErrorMessage());
        if (Boolean.TRUE.equals(outcome.getDoctorRejected())) {
            return AgentPrimaryCause.HUMAN_REJECTED;
        }
        if (outcome.getOutcomeLabel() == AgentOutcomeLabel.FAILED_TIMEOUT
                || errorCode.contains("timeout")
                || errorMessage.contains("timeout")
                || errorMessage.contains("超时")) {
            return AgentPrimaryCause.RUNTIME_TIMEOUT;
        }
        if (outcome.getOutcomeLabel() == AgentOutcomeLabel.FAILED_TOOL_ERROR
                || nonNegative(outcome.getToolErrorCount()) > 0
                || errorCode.contains("tool")
                || errorMessage.contains("tool")) {
            return AgentPrimaryCause.TOOL_UNRELIABLE;
        }
        if (outcome.getOutcomeLabel() == AgentOutcomeLabel.FAILED_PROTOCOL_ERROR
                || errorMessage.contains("协议")
                || errorMessage.contains("json")) {
            return AgentPrimaryCause.PROMPT_WEAK;
        }
        if (Boolean.FALSE.equals(outcome.getEvidenceSufficient())
                || outcome.getOutcomeLabel() == AgentOutcomeLabel.MANUAL_REVIEW_REQUIRED
                || isEvidenceLight(snapshot)) {
            return AgentPrimaryCause.INSUFFICIENT_EVIDENCE;
        }
        if (isRoutingWeak(snapshot)) {
            return AgentPrimaryCause.ROUTING_WEAK;
        }
        if (isMemoryMissing(snapshot)) {
            return AgentPrimaryCause.MEMORY_MISSING;
        }
        if (isMemoryNoise(snapshot)) {
            return AgentPrimaryCause.MEMORY_NOISE;
        }
        if (outcome.getOutcomeLabel() == AgentOutcomeLabel.SUCCESS_WITH_FALLBACK) {
            return isRoutingWeak(snapshot) ? AgentPrimaryCause.ROUTING_WEAK : AgentPrimaryCause.PROMPT_WEAK;
        }
        return AgentPrimaryCause.UNKNOWN;
    }

    private List<String> resolveSecondaryCauses(
            AgentPrimaryCause primaryCause,
            AgentRunOutcome outcome,
            AgentRunStrategySnapshot snapshot) {
        List<String> secondary = new ArrayList<>();
        addSecondary(secondary, primaryCause, isRoutingWeak(snapshot), AgentPrimaryCause.ROUTING_WEAK);
        addSecondary(secondary, primaryCause, isMemoryMissing(snapshot), AgentPrimaryCause.MEMORY_MISSING);
        addSecondary(secondary, primaryCause, isMemoryNoise(snapshot), AgentPrimaryCause.MEMORY_NOISE);
        addSecondary(secondary, primaryCause,
                Boolean.FALSE.equals(outcome != null ? outcome.getEvidenceSufficient() : null) || isEvidenceLight(snapshot),
                AgentPrimaryCause.INSUFFICIENT_EVIDENCE);
        addSecondary(secondary, primaryCause,
                outcome != null && outcome.getOutcomeLabel() == AgentOutcomeLabel.FAILED_PROTOCOL_ERROR,
                AgentPrimaryCause.PROMPT_WEAK);
        addSecondary(secondary, primaryCause,
                outcome != null && (outcome.getOutcomeLabel() == AgentOutcomeLabel.FAILED_TOOL_ERROR
                        || nonNegative(outcome.getToolErrorCount()) > 0),
                AgentPrimaryCause.TOOL_UNRELIABLE);
        return secondary;
    }

    private void addSecondary(List<String> secondary, AgentPrimaryCause primaryCause, boolean condition, AgentPrimaryCause cause) {
        if (condition && cause != null && cause != primaryCause && !secondary.contains(cause.name())) {
            secondary.add(cause.name());
        }
    }

    private Double resolveConfidence(
            AgentPrimaryCause primaryCause,
            AgentRunOutcome outcome,
            AgentRunStrategySnapshot snapshot) {
        return switch (primaryCause) {
            case HUMAN_REJECTED -> 0.99D;
            case RUNTIME_TIMEOUT -> 0.95D;
            case TOOL_UNRELIABLE -> 0.88D;
            case PROMPT_WEAK -> 0.90D;
            case INSUFFICIENT_EVIDENCE -> isEvidenceLight(snapshot) ? 0.84D : 0.76D;
            case ROUTING_WEAK -> 0.72D;
            case MEMORY_MISSING -> 0.78D;
            case MEMORY_NOISE -> 0.68D;
            case UNKNOWN -> outcome != null && outcome.getOutcomeLabel() == AgentOutcomeLabel.SUCCESS ? 0.40D : 0.55D;
        };
    }

    private Map<String, Object> buildEvidenceSummary(
            AgentRunOutcome outcome,
            AgentRunStrategySnapshot snapshot,
            AgentPrimaryCause primaryCause,
            List<String> secondaryCauses) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("outcomeLabel", outcome != null && outcome.getOutcomeLabel() != null ? outcome.getOutcomeLabel().name() : null);
        evidence.put("finalStatus", outcome != null ? outcome.getFinalStatus() : null);
        evidence.put("errorCode", outcome != null ? outcome.getErrorCode() : null);
        evidence.put("errorMessage", outcome != null ? outcome.getErrorMessage() : null);
        evidence.put("fallbackUsed", outcome != null ? outcome.getFallbackUsed() : null);
        evidence.put("manualReviewRequired", outcome != null ? outcome.getManualReviewRequired() : null);
        evidence.put("doctorRejected", outcome != null ? outcome.getDoctorRejected() : null);
        evidence.put("toolErrorCount", outcome != null ? outcome.getToolErrorCount() : null);
        evidence.put("promptVersion", snapshot != null ? snapshot.getPromptVersion() : null);
        evidence.put("selectorVersion", snapshot != null ? snapshot.getSelectorVersion() : null);
        evidence.put("selectedAgentIds", snapshot != null ? snapshot.getSelectedAgentIds() : List.of());
        evidence.put("toolSequence", snapshot != null ? snapshot.getToolSequence() : List.of());
        evidence.put("memoryRetrievalCount", snapshot != null ? snapshot.getMemoryRetrievalCount() : null);
        evidence.put("primaryCause", primaryCause != null ? primaryCause.name() : null);
        evidence.put("secondaryCauses", secondaryCauses != null ? secondaryCauses : List.of());
        return evidence;
    }

    private boolean isRoutingWeak(AgentRunStrategySnapshot snapshot) {
        if (snapshot == null || snapshot.getSelectedAgentIds() == null || snapshot.getSelectedAgentIds().isEmpty()) {
            return false;
        }
        Set<String> selected = new LinkedHashSet<>();
        snapshot.getSelectedAgentIds().stream()
                .filter(value -> value != null && !value.isBlank())
                .forEach(selected::add);
        boolean carePlanningOnly = selected.size() == 1 && selected.contains("doctor.CarePlanningAgent");
        boolean noToolsExecuted = snapshot.getToolSequence() == null || snapshot.getToolSequence().isEmpty();
        return carePlanningOnly && noToolsExecuted;
    }

    private boolean isMemoryMissing(AgentRunStrategySnapshot snapshot) {
        if (snapshot == null || snapshot.getMemoryProfile() == null) {
            return true;
        }
        Map<String, Object> profile = snapshot.getMemoryProfile();
        return !asBoolean(profile.get("hasMemorySnapshot"))
                && !asBoolean(profile.get("hasChatHistory"))
                && !asBoolean(profile.get("hasProfileSnapshot"));
    }

    private boolean isMemoryNoise(AgentRunStrategySnapshot snapshot) {
        return snapshot != null
                && nonNegative(snapshot.getMemoryRetrievalCount()) >= 8
                && snapshot.getMemoryProfile() != null
                && asBoolean(snapshot.getMemoryProfile().get("hasLongTermMemories"));
    }

    private boolean isEvidenceLight(AgentRunStrategySnapshot snapshot) {
        if (snapshot == null) {
            return true;
        }
        int toolCount = snapshot.getToolSequence() != null ? snapshot.getToolSequence().size() : 0;
        int memoryCount = nonNegative(snapshot.getMemoryRetrievalCount());
        return toolCount == 0 && memoryCount == 0;
    }

    private boolean asBoolean(Object value) {
        return value instanceof Boolean bool && bool;
    }

    private int nonNegative(Integer value) {
        return value != null ? value : 0;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    private String jsonOr(String current, Object next) {
        return next != null ? JSON.toJSONString(next) : current;
    }

    private List<String> parseStringList(String json) {
        if (isBlank(json)) {
            return Collections.emptyList();
        }
        return JSON.parseObject(json, STRING_LIST_TYPE);
    }

    private Map<String, Object> parseMap(String json) {
        if (isBlank(json)) {
            return Collections.emptyMap();
        }
        return JSON.parseObject(json, MAP_TYPE);
    }

    private <T> T valueOr(T current, T next) {
        return next != null ? next : current;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
