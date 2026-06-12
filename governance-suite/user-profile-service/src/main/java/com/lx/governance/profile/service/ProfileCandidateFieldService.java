package com.lx.governance.profile.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.lx.governance.profile.entity.UserProfileCandidateFieldEntity;
import com.lx.governance.profile.entity.UserProfileFieldConflictEntity;
import com.lx.governance.profile.mapper.CustomerMapper;
import com.lx.governance.profile.mapper.HealthAssessmentMapper;
import com.lx.governance.profile.mapper.UserProfileBehaviorPreferenceMapper;
import com.lx.governance.profile.mapper.UserProfileCandidateFieldMapper;
import com.lx.governance.profile.mapper.UserProfileFieldConflictMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProfileCandidateFieldService {

    private final UserProfileCandidateFieldMapper candidateFieldMapper;
    private final UserProfileFieldConflictMapper conflictMapper;
    private final RedisProfileService redisProfileService;
    private final CustomerMapper customerMapper;
    private final HealthAssessmentMapper healthAssessmentMapper;
    private final UserProfileBehaviorPreferenceMapper behaviorPreferenceMapper;

    private static final int AUTO_CONFIRM_HIT_THRESHOLD = 5;
    private static final double AUTO_CONFIRM_CONFIDENCE_THRESHOLD = 0.8;

    public void processCandidateField(String customerId,
                                      String fieldPath,
                                      String rawValue,
                                      double confidence,
                                      String source) {
        String normalizedValue = normalizeValue(rawValue);

        UserProfileCandidateFieldEntity existing = candidateFieldMapper.selectLatest(customerId, fieldPath, normalizedValue);
        if (existing != null) {
            candidateFieldMapper.incrementHitCount(existing.getId(), BigDecimal.valueOf(confidence), source);
            int nextHitCount = (existing.getHitCount() != null ? existing.getHitCount() : 0) + 1;
            if (nextHitCount > AUTO_CONFIRM_HIT_THRESHOLD && confidence >= AUTO_CONFIRM_CONFIDENCE_THRESHOLD) {
                confirmCandidateField(existing.getId(), "SYSTEM");
            }
            return;
        }

        List<UserProfileCandidateFieldEntity> activeFields = candidateFieldMapper.selectActiveByCustomerId(customerId);
        boolean hasConflict = activeFields.stream().anyMatch(field ->
                fieldPath.equals(field.getFieldPath())
                        && field.getNormalizedValue() != null
                        && !field.getNormalizedValue().equals(normalizedValue));

        UserProfileCandidateFieldEntity entity = new UserProfileCandidateFieldEntity();
        entity.setCustomerId(customerId);
        entity.setFieldPath(fieldPath);
        entity.setNormalizedValue(normalizedValue);
        entity.setRawValue(rawValue);
        entity.setConfidenceScore(BigDecimal.valueOf(confidence));
        entity.setStatus(hasConflict ? "CONFLICT" : "CANDIDATE");
        entity.setHitCount(1);
        entity.setSource(source);
        candidateFieldMapper.insert(entity);

        if (hasConflict) {
            UserProfileFieldConflictEntity conflict = new UserProfileFieldConflictEntity();
            conflict.setCustomerId(customerId);
            conflict.setCandidateFieldId(entity.getId());
            conflict.setConflictTargetType("CANDIDATE");
            conflict.setConflictTargetValue(activeFields.stream()
                    .filter(field -> fieldPath.equals(field.getFieldPath()))
                    .map(UserProfileCandidateFieldEntity::getNormalizedValue)
                    .findFirst()
                    .orElse(""));
            conflict.setConflictReason("field_value_conflict");
            conflict.setResolutionStatus("OPEN");
            conflictMapper.insert(conflict);
        }
    }

    public void confirmCandidateField(Long candidateFieldId, String reviewer) {
        UserProfileCandidateFieldEntity candidate = candidateFieldMapper.selectById(candidateFieldId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate field not found: " + candidateFieldId);
        }

        resolveConflicts(candidateFieldId, "RESOLVED");
        writeBackToFormalProfile(candidate);
        candidateFieldMapper.updateStatus(candidateFieldId, "CONFIRMED", reviewer, LocalDateTime.now());
        redisProfileService.refreshProfile(candidate.getCustomerId());
    }

    public void rejectCandidateField(Long candidateFieldId, String reviewer) {
        UserProfileCandidateFieldEntity candidate = candidateFieldMapper.selectById(candidateFieldId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate field not found: " + candidateFieldId);
        }

        resolveConflicts(candidateFieldId, "REJECTED");
        candidateFieldMapper.updateStatus(candidateFieldId, "REJECTED", reviewer, LocalDateTime.now());
    }

    private void resolveConflicts(Long candidateFieldId, String resolutionStatus) {
        List<UserProfileFieldConflictEntity> conflicts = conflictMapper.selectByCandidateFieldId(candidateFieldId);
        for (UserProfileFieldConflictEntity conflict : conflicts) {
            if ("OPEN".equalsIgnoreCase(conflict.getResolutionStatus())) {
                conflictMapper.updateResolutionStatus(conflict.getId(), resolutionStatus);
            }
        }
    }

    private String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private void writeBackToFormalProfile(UserProfileCandidateFieldEntity candidate) {
        String fieldPath = candidate.getFieldPath();
        Object fieldValue = parseFieldValue(fieldPath, candidate.getRawValue());

        if (fieldPath.startsWith("basic.")) {
            customerMapper.updateField(candidate.getCustomerId(), fieldPath.substring("basic.".length()), fieldValue);
            return;
        }

        if (fieldPath.startsWith("health.")) {
            healthAssessmentMapper.updateLatestField(
                    candidate.getCustomerId(),
                    fieldPath.substring("health.".length()),
                    fieldValue);
            return;
        }

        if (fieldPath.startsWith("behavior.")) {
            String fieldName = fieldPath.substring("behavior.".length());
            if (behaviorPreferenceMapper.selectByCustomerId(candidate.getCustomerId()) == null) {
                com.lx.governance.profile.entity.UserProfileBehaviorPreferenceEntity entity =
                        new com.lx.governance.profile.entity.UserProfileBehaviorPreferenceEntity();
                entity.setCustomerId(candidate.getCustomerId());
                entity.setDietPreference("behavior.dietPreference".equals(fieldPath) ? (String) fieldValue : null);
                entity.setExerciseFrequency("behavior.exerciseFrequency".equals(fieldPath) ? (String) fieldValue : null);
                entity.setCheckInStreak("behavior.checkInStreak".equals(fieldPath) ? (Integer) fieldValue : null);
                behaviorPreferenceMapper.insert(entity);
            } else {
                behaviorPreferenceMapper.updateField(candidate.getCustomerId(), fieldName, fieldValue);
            }
        }
    }

    private Object parseFieldValue(String fieldPath, String rawValue) {
        if (fieldPath == null) {
            return rawValue;
        }

        return switch (fieldPath) {
            case "basic.age" -> Integer.valueOf(rawValue);
            case "health.bmi", "health.bloodSugar" -> Double.valueOf(rawValue);
            case "health.healthGoals" -> rawValue;
            default -> rawValue;
        };
    }
}
