package com.lx.governance.profile.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.lx.governance.profile.entity.UserProfileCandidateFieldEntity;
import com.lx.governance.profile.entity.UserProfileFieldConflictEntity;
import com.lx.governance.profile.entity.UserProfileFieldReviewAuditEntity;
import com.lx.governance.profile.mapper.CustomerMapper;
import com.lx.governance.profile.mapper.HealthAssessmentMapper;
import com.lx.governance.profile.mapper.UserProfileBehaviorPreferenceMapper;
import com.lx.governance.profile.mapper.UserProfileCandidateFieldMapper;
import com.lx.governance.profile.mapper.UserProfileFieldConflictMapper;
import com.lx.governance.profile.mapper.UserProfileFieldReviewAuditMapper;

@Service
public class ProfileCandidateFieldService {

    private final UserProfileCandidateFieldMapper candidateFieldMapper;
    private final UserProfileFieldConflictMapper conflictMapper;
    private final RedisProfileService redisProfileService;
    private final CustomerMapper customerMapper;
    private final HealthAssessmentMapper healthAssessmentMapper;
    private final UserProfileBehaviorPreferenceMapper behaviorPreferenceMapper;
    private final UserProfileFieldReviewAuditMapper reviewAuditMapper;
    private final SemanticProfileConflictDetector conflictDetector;

    private static final int AUTO_CONFIRM_HIT_THRESHOLD = 5;
    private static final double AUTO_CONFIRM_CONFIDENCE_THRESHOLD = 0.8;

    public ProfileCandidateFieldService(
            UserProfileCandidateFieldMapper candidateFieldMapper,
            UserProfileFieldConflictMapper conflictMapper,
            RedisProfileService redisProfileService,
            CustomerMapper customerMapper,
            HealthAssessmentMapper healthAssessmentMapper,
            UserProfileBehaviorPreferenceMapper behaviorPreferenceMapper,
            UserProfileFieldReviewAuditMapper reviewAuditMapper) {
        this(candidateFieldMapper, conflictMapper, redisProfileService, customerMapper, healthAssessmentMapper,
                behaviorPreferenceMapper, reviewAuditMapper, new SemanticProfileConflictDetector());
    }

    public ProfileCandidateFieldService(
            UserProfileCandidateFieldMapper candidateFieldMapper,
            UserProfileFieldConflictMapper conflictMapper,
            RedisProfileService redisProfileService,
            CustomerMapper customerMapper,
            HealthAssessmentMapper healthAssessmentMapper,
            UserProfileBehaviorPreferenceMapper behaviorPreferenceMapper,
            UserProfileFieldReviewAuditMapper reviewAuditMapper,
            SemanticProfileConflictDetector conflictDetector) {
        this.candidateFieldMapper = candidateFieldMapper;
        this.conflictMapper = conflictMapper;
        this.redisProfileService = redisProfileService;
        this.customerMapper = customerMapper;
        this.healthAssessmentMapper = healthAssessmentMapper;
        this.behaviorPreferenceMapper = behaviorPreferenceMapper;
        this.reviewAuditMapper = reviewAuditMapper;
        this.conflictDetector = conflictDetector;
    }

    public void processCandidateField(String customerId,
                                      String fieldPath,
                                      String rawValue,
                                      double confidence,
                                      String source) {
        SemanticProfileFingerprint fingerprint = conflictDetector.fingerprint(fieldPath, rawValue);
        String normalizedValue = fingerprint.getNormalizedValue();

        UserProfileCandidateFieldEntity existing = candidateFieldMapper.selectLatest(customerId, fieldPath, normalizedValue);
        if (existing != null) {
            candidateFieldMapper.incrementHitCount(existing.getId(), BigDecimal.valueOf(confidence), source);
            int nextHitCount = (existing.getHitCount() != null ? existing.getHitCount() : 0) + 1;
            if (nextHitCount > AUTO_CONFIRM_HIT_THRESHOLD && confidence >= AUTO_CONFIRM_CONFIDENCE_THRESHOLD) {
                confirmCandidateField(existing.getId(), "SYSTEM", "auto_confirm", null);
            }
            return;
        }

        List<UserProfileCandidateFieldEntity> activeFields = candidateFieldMapper.selectActiveByCustomerId(customerId);
        if (activeFields == null) {
            activeFields = List.of();
        }
        ProfileConflictTarget conflictTarget = findBestConflict(customerId, fieldPath, rawValue, fingerprint, activeFields);
        boolean hasConflict = conflictTarget != null;

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
            conflict.setConflictTargetType(conflictTarget.targetType());
            conflict.setConflictTargetValue(conflictTarget.targetValue());
            conflict.setConflictReason(conflictTarget.analysis().getConflictReason());
            conflict.setResolutionStatus("OPEN");
            conflictMapper.insert(conflict);
        }
    }

    public void confirmCandidateField(Long candidateFieldId, String reviewer) {
        confirmCandidateField(candidateFieldId, reviewer, "manual_confirm", null);
    }

    public void confirmCandidateField(Long candidateFieldId, String reviewer, String resolutionReason, String remarks) {
        UserProfileCandidateFieldEntity candidate = candidateFieldMapper.selectById(candidateFieldId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate field not found: " + candidateFieldId);
        }

        resolveConflicts(candidateFieldId, "RESOLVED", resolutionReason, remarks);
        writeBackToFormalProfile(candidate);
        candidateFieldMapper.updateStatus(candidateFieldId, "CONFIRMED", reviewer, LocalDateTime.now());
        redisProfileService.refreshProfile(candidate.getCustomerId());
        recordAudit(candidate, "CONFIRM", "CONFIRMED", reviewer, resolutionReason, remarks, candidate.getNormalizedValue());
    }

    public void rejectCandidateField(Long candidateFieldId, String reviewer) {
        rejectCandidateField(candidateFieldId, reviewer, "manual_reject", null);
    }

    public void rejectCandidateField(Long candidateFieldId, String reviewer, String resolutionReason, String remarks) {
        UserProfileCandidateFieldEntity candidate = candidateFieldMapper.selectById(candidateFieldId);
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate field not found: " + candidateFieldId);
        }

        resolveConflicts(candidateFieldId, "REJECTED", resolutionReason, remarks);
        candidateFieldMapper.updateStatus(candidateFieldId, "REJECTED", reviewer, LocalDateTime.now());
        recordAudit(candidate, "REJECT", "REJECTED", reviewer, resolutionReason, remarks, candidate.getNormalizedValue());
    }

    public String getCurrentFormalValue(String customerId, String fieldPath) {
        return readFormalValue(customerId, fieldPath);
    }

    private void resolveConflicts(Long candidateFieldId, String resolutionStatus, String resolutionReason, String resolutionNote) {
        List<UserProfileFieldConflictEntity> conflicts = conflictMapper.selectByCandidateFieldId(candidateFieldId);
        for (UserProfileFieldConflictEntity conflict : conflicts) {
            if ("OPEN".equalsIgnoreCase(conflict.getResolutionStatus())) {
                conflictMapper.updateResolutionStatus(conflict.getId(), resolutionStatus, resolutionReason, resolutionNote);
            }
        }
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

    private ProfileConflictTarget findBestConflict(
            String customerId,
            String fieldPath,
            String rawValue,
            SemanticProfileFingerprint fingerprint,
            List<UserProfileCandidateFieldEntity> activeFields) {
        List<ProfileConflictTarget> conflicts = activeFields.stream()
                .filter(field -> fieldPath.equals(field.getFieldPath()))
                .map(field -> {
                    SemanticProfileConflictAnalysis analysis = conflictDetector.analyze(
                            fingerprint,
                            conflictDetector.fingerprint(field.getFieldPath(), field.getRawValue()));
                    return analysis.isConflict()
                            ? new ProfileConflictTarget("CANDIDATE", field.getRawValue(), analysis)
                            : null;
                })
                .filter(conflict -> conflict != null)
                .collect(java.util.stream.Collectors.toList());

        String formalValue = readFormalValue(customerId, fieldPath);
        if (formalValue != null && !formalValue.isBlank()) {
            SemanticProfileConflictAnalysis analysis = conflictDetector.analyze(
                    fingerprint,
                    conflictDetector.fingerprint(fieldPath, formalValue));
            if (analysis.isConflict()) {
                conflicts.add(new ProfileConflictTarget("PROFILE", formalValue, analysis));
            }
        }

        return conflicts.stream()
                .max(Comparator.comparingDouble(conflict -> conflict.analysis().getConflictScore()))
                .orElse(null);
    }

    private String readFormalValue(String customerId, String fieldPath) {
        if (fieldPath == null) {
            return null;
        }

        if (fieldPath.startsWith("basic.")) {
            var customer = customerMapper.selectByCustomerId(customerId);
            if (customer == null) {
                return null;
            }
            return switch (fieldPath) {
                case "basic.name" -> customer.getName();
                case "basic.age" -> customer.getAge() != null ? String.valueOf(customer.getAge()) : null;
                case "basic.gender" -> customer.getGender();
                case "basic.phone" -> customer.getPhone();
                case "basic.email" -> customer.getEmail();
                default -> null;
            };
        }

        if (fieldPath.startsWith("health.")) {
            var assessment = healthAssessmentMapper.selectLatestByCustomerId(customerId);
            if (assessment == null) {
                return null;
            }
            return switch (fieldPath) {
                case "health.bmi" -> assessment.getBmi() != null ? String.valueOf(assessment.getBmi()) : null;
                case "health.bloodSugar" -> assessment.getBloodSugar() != null ? String.valueOf(assessment.getBloodSugar()) : null;
                case "health.bloodPressure" -> assessment.getBloodPressure();
                case "health.healthGoals" -> assessment.getHealthGoals();
                default -> null;
            };
        }

        if (fieldPath.startsWith("behavior.")) {
            var behavior = behaviorPreferenceMapper.selectByCustomerId(customerId);
            if (behavior == null) {
                return null;
            }
            return switch (fieldPath) {
                case "behavior.dietPreference" -> behavior.getDietPreference();
                case "behavior.exerciseFrequency" -> behavior.getExerciseFrequency();
                case "behavior.checkInStreak" -> behavior.getCheckInStreak() != null ? String.valueOf(behavior.getCheckInStreak()) : null;
                default -> null;
            };
        }
        return null;
    }

    private void recordAudit(
            UserProfileCandidateFieldEntity candidate,
            String actionType,
            String resultStatus,
            String reviewer,
            String resolutionReason,
            String remarks,
            String relatedFormalValue) {
        if (reviewAuditMapper == null || candidate == null) {
            return;
        }
        UserProfileFieldReviewAuditEntity audit = new UserProfileFieldReviewAuditEntity();
        audit.setCandidateFieldId(candidate.getId());
        audit.setCustomerId(candidate.getCustomerId());
        audit.setFieldPath(candidate.getFieldPath());
        audit.setActionType(actionType);
        audit.setResultStatus(resultStatus);
        audit.setReviewer(reviewer);
        audit.setResolutionReason(resolutionReason);
        audit.setRemarks(remarks);
        audit.setRelatedFormalValue(relatedFormalValue);
        reviewAuditMapper.insert(audit);
    }

    private record ProfileConflictTarget(
            String targetType,
            String targetValue,
            SemanticProfileConflictAnalysis analysis) {
    }
}
