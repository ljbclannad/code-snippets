package com.health.agent.learning.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.health.agent.learning.entity.AgentRunOutcomeEntity;
import com.health.agent.learning.mapper.AgentRunOutcomeMapper;
import com.health.agent.learning.model.AgentRunOutcome;

@Service
public class DefaultAgentOutcomeRecorder implements AgentOutcomeRecorder {

    private final AgentRunOutcomeMapper mapper;

    public DefaultAgentOutcomeRecorder(AgentRunOutcomeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void createOrUpdate(AgentRunOutcome outcome) {
        if (outcome == null || isBlank(outcome.getRunId())) {
            return;
        }
        AgentRunOutcomeEntity existing = selectByRunId(outcome.getRunId()).orElse(null);
        if (existing == null) {
            AgentRunOutcomeEntity entity = toEntity(outcome);
            LocalDateTime now = LocalDateTime.now();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            mapper.insert(entity);
            return;
        }
        merge(existing, outcome);
        existing.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(existing);
    }

    @Override
    public Optional<AgentRunOutcome> findByRunId(String runId) {
        return selectByRunId(runId).map(this::toModel);
    }

    private Optional<AgentRunOutcomeEntity> selectByRunId(String runId) {
        if (isBlank(runId)) {
            return Optional.empty();
        }
        LambdaQueryWrapper<AgentRunOutcomeEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentRunOutcomeEntity::getRunId, runId).last("LIMIT 1");
        return Optional.ofNullable(mapper.selectOne(wrapper));
    }

    private AgentRunOutcomeEntity toEntity(AgentRunOutcome outcome) {
        AgentRunOutcomeEntity entity = new AgentRunOutcomeEntity();
        merge(entity, outcome);
        return entity;
    }

    private void merge(AgentRunOutcomeEntity entity, AgentRunOutcome outcome) {
        entity.setRunId(valueOr(entity.getRunId(), outcome.getRunId()));
        entity.setSessionId(valueOr(entity.getSessionId(), outcome.getSessionId()));
        entity.setScene(valueOr(entity.getScene(), outcome.getScene()));
        entity.setTaskType(valueOr(entity.getTaskType(), outcome.getTaskType()));
        entity.setCustomerId(valueOr(entity.getCustomerId(), outcome.getCustomerId()));
        entity.setDoctorId(valueOr(entity.getDoctorId(), outcome.getDoctorId()));
        entity.setFinalStatus(valueOr(entity.getFinalStatus(), outcome.getFinalStatus()));
        entity.setOutcomeLabel(outcome.getOutcomeLabel() != null ? outcome.getOutcomeLabel().name() : entity.getOutcomeLabel());
        entity.setFallbackUsed(valueOr(entity.getFallbackUsed(), outcome.getFallbackUsed()));
        entity.setManualReviewRequired(valueOr(entity.getManualReviewRequired(), outcome.getManualReviewRequired()));
        entity.setManualOverride(valueOr(entity.getManualOverride(), outcome.getManualOverride()));
        entity.setDoctorAccepted(valueOr(entity.getDoctorAccepted(), outcome.getDoctorAccepted()));
        entity.setDoctorRejected(valueOr(entity.getDoctorRejected(), outcome.getDoctorRejected()));
        entity.setEvidenceSufficient(valueOr(entity.getEvidenceSufficient(), outcome.getEvidenceSufficient()));
        entity.setToolErrorCount(valueOr(entity.getToolErrorCount(), outcome.getToolErrorCount()));
        entity.setRetryCount(valueOr(entity.getRetryCount(), outcome.getRetryCount()));
        entity.setDurationMs(valueOr(entity.getDurationMs(), outcome.getDurationMs()));
        entity.setErrorCode(valueOr(entity.getErrorCode(), outcome.getErrorCode()));
        entity.setErrorMessage(valueOr(entity.getErrorMessage(), outcome.getErrorMessage()));
    }

    private AgentRunOutcome toModel(AgentRunOutcomeEntity entity) {
        return AgentRunOutcome.builder()
                .runId(entity.getRunId())
                .sessionId(entity.getSessionId())
                .scene(entity.getScene())
                .taskType(entity.getTaskType())
                .customerId(entity.getCustomerId())
                .doctorId(entity.getDoctorId())
                .finalStatus(entity.getFinalStatus())
                .outcomeLabel(entity.getOutcomeLabel() != null
                        ? com.health.agent.learning.enums.AgentOutcomeLabel.valueOf(entity.getOutcomeLabel())
                        : null)
                .fallbackUsed(entity.getFallbackUsed())
                .manualReviewRequired(entity.getManualReviewRequired())
                .manualOverride(entity.getManualOverride())
                .doctorAccepted(entity.getDoctorAccepted())
                .doctorRejected(entity.getDoctorRejected())
                .evidenceSufficient(entity.getEvidenceSufficient())
                .toolErrorCount(entity.getToolErrorCount())
                .retryCount(entity.getRetryCount())
                .durationMs(entity.getDurationMs())
                .errorCode(entity.getErrorCode())
                .errorMessage(entity.getErrorMessage())
                .build();
    }

    private <T> T valueOr(T current, T next) {
        return next != null ? next : current;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
