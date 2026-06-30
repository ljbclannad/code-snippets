package com.health.agent.learning.service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.health.agent.learning.entity.AgentOutcomeAttributionEntity;
import com.health.agent.learning.enums.AgentPrimaryCause;
import com.health.agent.learning.mapper.AgentOutcomeAttributionMapper;
import com.health.agent.learning.model.AgentOutcomeAttribution;

@Service
public class DefaultAgentOutcomeAttributionService implements AgentOutcomeAttributionService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AgentOutcomeAttributionMapper mapper;

    public DefaultAgentOutcomeAttributionService(AgentOutcomeAttributionMapper mapper) {
        this.mapper = mapper;
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
