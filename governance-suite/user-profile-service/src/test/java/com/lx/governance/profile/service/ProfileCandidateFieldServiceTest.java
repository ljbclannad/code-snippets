package com.lx.governance.profile.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.lx.governance.profile.entity.UserProfileCandidateFieldEntity;
import com.lx.governance.profile.entity.UserProfileBehaviorPreferenceEntity;
import com.lx.governance.profile.entity.UserProfileFieldConflictEntity;
import com.lx.governance.profile.mapper.CustomerMapper;
import com.lx.governance.profile.mapper.HealthAssessmentMapper;
import com.lx.governance.profile.mapper.UserProfileBehaviorPreferenceMapper;
import com.lx.governance.profile.mapper.UserProfileCandidateFieldMapper;
import com.lx.governance.profile.mapper.UserProfileFieldConflictMapper;

@ExtendWith(MockitoExtension.class)
class ProfileCandidateFieldServiceTest {

    @Mock
    private UserProfileCandidateFieldMapper candidateFieldMapper;
    @Mock
    private UserProfileFieldConflictMapper conflictMapper;
    @Mock
    private RedisProfileService redisProfileService;
    @Mock
    private CustomerMapper customerMapper;
    @Mock
    private HealthAssessmentMapper healthAssessmentMapper;
    @Mock
    private UserProfileBehaviorPreferenceMapper behaviorPreferenceMapper;

    private ProfileCandidateFieldService profileCandidateFieldService;

    @BeforeEach
    void setUp() {
        profileCandidateFieldService = new ProfileCandidateFieldService(
                candidateFieldMapper,
                conflictMapper,
                redisProfileService,
                customerMapper,
                healthAssessmentMapper,
                behaviorPreferenceMapper);
    }

    @Test
    void shouldCreateCandidateFieldWhenNoConflict() {
        when(candidateFieldMapper.selectLatest("C001", "behavior.dietPreference", "低碳水"))
                .thenReturn(null);
        when(candidateFieldMapper.selectActiveByCustomerId("C001")).thenReturn(List.of());

        profileCandidateFieldService.processCandidateField("C001", "behavior.dietPreference", "低碳水", 0.82, "chat");

        verify(candidateFieldMapper).insert(any(UserProfileCandidateFieldEntity.class));
        verify(conflictMapper, never()).insert(any());
    }

    @Test
    void shouldCreateConflictWhenSameFieldPathHasDifferentValue() {
        when(candidateFieldMapper.selectLatest("C001", "behavior.dietPreference", "重辣"))
                .thenReturn(null);

        UserProfileCandidateFieldEntity existing = new UserProfileCandidateFieldEntity();
        existing.setId(2L);
        existing.setCustomerId("C001");
        existing.setFieldPath("behavior.dietPreference");
        existing.setNormalizedValue("低碳水");
        existing.setStatus("CANDIDATE");
        when(candidateFieldMapper.selectActiveByCustomerId("C001")).thenReturn(List.of(existing));

        profileCandidateFieldService.processCandidateField("C001", "behavior.dietPreference", "重辣", 0.86, "chat");

        verify(candidateFieldMapper).insert(any(UserProfileCandidateFieldEntity.class));
        verify(conflictMapper).insert(any(UserProfileFieldConflictEntity.class));
    }

    @Test
    void shouldConfirmFieldAndRefreshRedisProfile() {
        UserProfileCandidateFieldEntity candidate = new UserProfileCandidateFieldEntity();
        candidate.setId(9L);
        candidate.setCustomerId("C001");
        candidate.setFieldPath("basic.name");
        candidate.setNormalizedValue("李四");
        candidate.setRawValue("李四");
        candidate.setConfidenceScore(BigDecimal.valueOf(0.91));
        candidate.setStatus("CONFLICT");
        when(candidateFieldMapper.selectById(9L)).thenReturn(candidate);

        UserProfileFieldConflictEntity conflict = new UserProfileFieldConflictEntity();
        conflict.setId(5L);
        conflict.setCandidateFieldId(9L);
        conflict.setResolutionStatus("OPEN");
        when(conflictMapper.selectByCandidateFieldId(9L)).thenReturn(List.of(conflict));

        profileCandidateFieldService.confirmCandidateField(9L, "doctor-a");

        verify(customerMapper).updateField("C001", "name", "李四");
        verify(candidateFieldMapper).updateStatus(eq(9L), eq("CONFIRMED"), eq("doctor-a"), any());
        verify(conflictMapper).updateResolutionStatus(5L, "RESOLVED");
        verify(redisProfileService).refreshProfile("C001");
    }

    @Test
    void shouldAutoConfirmBehaviorFieldAfterHitThreshold() {
        UserProfileCandidateFieldEntity existing = new UserProfileCandidateFieldEntity();
        existing.setId(11L);
        existing.setCustomerId("C001");
        existing.setFieldPath("behavior.dietPreference");
        existing.setNormalizedValue("低碳水");
        existing.setRawValue("低碳水");
        existing.setHitCount(5);
        existing.setStatus("CANDIDATE");

        when(candidateFieldMapper.selectLatest("C001", "behavior.dietPreference", "低碳水")).thenReturn(existing);
        when(candidateFieldMapper.selectById(11L)).thenReturn(existing);
        when(conflictMapper.selectByCandidateFieldId(11L)).thenReturn(List.of());
        when(behaviorPreferenceMapper.selectByCustomerId("C001")).thenReturn(null);

        profileCandidateFieldService.processCandidateField("C001", "behavior.dietPreference", "低碳水", 0.85, "chat");

        verify(candidateFieldMapper).incrementHitCount(eq(11L), any(), eq("chat"));
        verify(behaviorPreferenceMapper).insert(any(UserProfileBehaviorPreferenceEntity.class));
        verify(candidateFieldMapper).updateStatus(eq(11L), eq("CONFIRMED"), eq("SYSTEM"), any());
        verify(redisProfileService).refreshProfile("C001");
    }
}
