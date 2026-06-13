package com.lx.governance.profile.service;

import com.lx.governance.profile.dto.*;
import com.lx.governance.profile.entity.*;
import com.lx.governance.profile.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final CustomerMapper customerMapper;
    private final HealthAssessmentMapper healthAssessmentMapper;
    private final AiUserMemoryMapper aiUserMemoryMapper;
    private final UserProfileBehaviorPreferenceMapper userProfileBehaviorPreferenceMapper;

    @Cacheable(value = "user:profile", key = "#customerId", unless = "#result == null")
    public UserProfileDTO buildUserProfile(String customerId) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("开始构建用户画像: customerId={}", customerId);

            BasicInfoDTO basicInfo = buildBasicInfo(customerId);
            if (basicInfo == null) {
                log.warn("用户不存在: customerId={}", customerId);
                return null;
            }

            HealthProfileDTO healthProfile = buildHealthProfile(customerId);
            BehaviorPreferencesDTO behaviorPreferences = buildBehaviorPreferences(customerId);
            List<GlobalMemoryDTO> globalMemories = buildGlobalMemories(customerId);

            UserProfileDTO profile = UserProfileDTO.builder()
                .basicInfo(basicInfo)
                .healthProfile(healthProfile)
                .behaviorPreferences(behaviorPreferences)
                .globalMemories(globalMemories)
                .build();

            long cost = System.currentTimeMillis() - startTime;
            log.info("用户画像构建完成: customerId={}, cost={}ms", customerId, cost);

            return profile;

        } catch (Exception e) {
            log.error("构建用户画像失败: customerId={}", customerId, e);
            throw new RuntimeException("构建用户画像失败", e);
        }
    }

    private BasicInfoDTO buildBasicInfo(String customerId) {
        CustomerEntity customer = customerMapper.selectByCustomerId(customerId);
        if (customer == null) {
            return null;
        }

        return BasicInfoDTO.builder()
            .customerId(customer.getCustomerId())
            .name(customer.getName())
            .age(customer.getAge())
            .gender(customer.getGender())
            .phone(customer.getPhone())
            .email(customer.getEmail())
            .build();
    }

    private HealthProfileDTO buildHealthProfile(String customerId) {
        HealthAssessmentEntity assessment = healthAssessmentMapper.selectLatestByCustomerId(customerId);

        if (assessment == null) {
            return HealthProfileDTO.empty();
        }

        return HealthProfileDTO.builder()
            .bmi(assessment.getBmi())
            .bloodPressure(assessment.getBloodPressure())
            .bloodSugar(assessment.getBloodSugar())
            .latestAssessmentDate(assessment.getAssessmentDate())
            .healthGoals(parseHealthGoals(assessment.getHealthGoals()))
            .build();
    }

    private BehaviorPreferencesDTO buildBehaviorPreferences(String customerId) {
        UserProfileBehaviorPreferenceEntity preference = userProfileBehaviorPreferenceMapper.selectByCustomerId(customerId);
        if (preference != null) {
            return BehaviorPreferencesDTO.builder()
                .dietPreference(preference.getDietPreference())
                .exerciseFrequency(preference.getExerciseFrequency())
                .checkInStreak(preference.getCheckInStreak())
                .build();
        }

        return BehaviorPreferencesDTO.builder()
            .dietPreference("均衡饮食")
            .exerciseFrequency("每周3次")
            .checkInStreak(0)
            .build();
    }

    private List<GlobalMemoryDTO> buildGlobalMemories(String customerId) {
        List<AiUserMemoryEntity> memories = aiUserMemoryMapper.selectByCustomerId(customerId);

        return memories.stream()
            .filter(m -> m.getIsDeleted() == 0)
            .filter(m -> m.getVerified() == 1)
            .map(m -> GlobalMemoryDTO.builder()
                .type(m.getMemoryType())
                .key(m.getMemoryKey())
                .content(m.getMemoryContent())
                .verified(m.getVerified() == 1)
                .build())
            .collect(Collectors.toList());
    }

    private List<String> parseHealthGoals(String healthGoalsJson) {
        if (healthGoalsJson == null || healthGoalsJson.isEmpty()) {
            return List.of();
        }
        return List.of(healthGoalsJson.split(","));
    }
}
