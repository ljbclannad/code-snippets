package com.health.agent.learning.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.health.agent.learning.entity.AgentRunStrategySnapshotEntity;
import com.health.agent.learning.mapper.AgentRunStrategySnapshotMapper;
import com.health.agent.learning.model.AgentRunStrategySnapshot;

@Service
public class DefaultAgentRunStrategySnapshotRecorder implements AgentRunStrategySnapshotRecorder {

    private final AgentRunStrategySnapshotMapper mapper;

    public DefaultAgentRunStrategySnapshotRecorder(AgentRunStrategySnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void createOrUpdate(AgentRunStrategySnapshot snapshot) {
        if (snapshot == null || isBlank(snapshot.getRunId())) {
            return;
        }
        AgentRunStrategySnapshotEntity existing = selectByRunId(snapshot.getRunId()).orElse(null);
        if (existing == null) {
            AgentRunStrategySnapshotEntity entity = toEntity(snapshot);
            LocalDateTime now = LocalDateTime.now();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            mapper.insert(entity);
            return;
        }
        merge(existing, snapshot);
        existing.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(existing);
    }

    @Override
    public Optional<AgentRunStrategySnapshot> findByRunId(String runId) {
        return selectByRunId(runId).map(this::toModel);
    }

    private Optional<AgentRunStrategySnapshotEntity> selectByRunId(String runId) {
        if (isBlank(runId)) {
            return Optional.empty();
        }
        LambdaQueryWrapper<AgentRunStrategySnapshotEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentRunStrategySnapshotEntity::getRunId, runId).last("LIMIT 1");
        return Optional.ofNullable(mapper.selectOne(wrapper));
    }

    private AgentRunStrategySnapshotEntity toEntity(AgentRunStrategySnapshot snapshot) {
        AgentRunStrategySnapshotEntity entity = new AgentRunStrategySnapshotEntity();
        merge(entity, snapshot);
        return entity;
    }

    private void merge(AgentRunStrategySnapshotEntity entity, AgentRunStrategySnapshot snapshot) {
        entity.setRunId(valueOr(entity.getRunId(), snapshot.getRunId()));
        entity.setSessionId(valueOr(entity.getSessionId(), snapshot.getSessionId()));
        entity.setScene(valueOr(entity.getScene(), snapshot.getScene()));
        entity.setTaskType(valueOr(entity.getTaskType(), snapshot.getTaskType()));
        entity.setPromptVersion(valueOr(entity.getPromptVersion(), snapshot.getPromptVersion()));
        entity.setSelectorVersion(valueOr(entity.getSelectorVersion(), snapshot.getSelectorVersion()));
        entity.setSelectedAgentIdsJson(jsonOr(entity.getSelectedAgentIdsJson(), snapshot.getSelectedAgentIds()));
        entity.setSelectionMode(valueOr(entity.getSelectionMode(), snapshot.getSelectionMode()));
        entity.setToolCatalogVersion(valueOr(entity.getToolCatalogVersion(), snapshot.getToolCatalogVersion()));
        entity.setToolSequenceJson(jsonOr(entity.getToolSequenceJson(), snapshot.getToolSequence()));
        entity.setMemoryProfileJson(jsonOr(entity.getMemoryProfileJson(), snapshot.getMemoryProfile()));
        entity.setMemoryRetrievalCount(valueOr(entity.getMemoryRetrievalCount(), snapshot.getMemoryRetrievalCount()));
        entity.setMemoryRerankApplied(valueOr(entity.getMemoryRerankApplied(), snapshot.getMemoryRerankApplied()));
        entity.setDegradationLevel(valueOr(entity.getDegradationLevel(), snapshot.getDegradationLevel()));
        entity.setParallelEnabled(valueOr(entity.getParallelEnabled(), snapshot.getParallelEnabled()));
        entity.setFallbackAllowed(valueOr(entity.getFallbackAllowed(), snapshot.getFallbackAllowed()));
        entity.setTimeoutMillis(valueOr(entity.getTimeoutMillis(), snapshot.getTimeoutMillis()));
        entity.setPatientVisibleOnly(valueOr(entity.getPatientVisibleOnly(), snapshot.getPatientVisibleOnly()));
    }

    private AgentRunStrategySnapshot toModel(AgentRunStrategySnapshotEntity entity) {
        return AgentRunStrategySnapshot.builder()
                .runId(entity.getRunId())
                .sessionId(entity.getSessionId())
                .scene(entity.getScene())
                .taskType(entity.getTaskType())
                .promptVersion(entity.getPromptVersion())
                .selectorVersion(entity.getSelectorVersion())
                .selectedAgentIds(parseStringList(entity.getSelectedAgentIdsJson()))
                .selectionMode(entity.getSelectionMode())
                .toolCatalogVersion(entity.getToolCatalogVersion())
                .toolSequence(parseStringList(entity.getToolSequenceJson()))
                .memoryProfile(parseMap(entity.getMemoryProfileJson()))
                .memoryRetrievalCount(entity.getMemoryRetrievalCount())
                .memoryRerankApplied(entity.getMemoryRerankApplied())
                .degradationLevel(entity.getDegradationLevel())
                .parallelEnabled(entity.getParallelEnabled())
                .fallbackAllowed(entity.getFallbackAllowed())
                .timeoutMillis(entity.getTimeoutMillis())
                .patientVisibleOnly(entity.getPatientVisibleOnly())
                .build();
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> parseMap(String json) {
        if (isBlank(json)) {
            return null;
        }
        return JSON.parseObject(json, java.util.Map.class);
    }

    @SuppressWarnings("unchecked")
    private java.util.List<String> parseStringList(String json) {
        if (isBlank(json)) {
            return java.util.List.of();
        }
        return JSON.parseObject(json, java.util.List.class);
    }

    private String jsonOr(String current, Object next) {
        if (next == null) {
            return current;
        }
        return JSON.toJSONString(next);
    }

    private <T> T valueOr(T current, T next) {
        return next != null ? next : current;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
