package com.lx.governance.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDTO {
    private BasicInfoDTO basicInfo;
    private HealthProfileDTO healthProfile;
    private BehaviorPreferencesDTO behaviorPreferences;
    private List<GlobalMemoryDTO> globalMemories;

    public static UserProfileDTO empty(String customerId) {
        return UserProfileDTO.builder()
            .basicInfo(BasicInfoDTO.builder().customerId(customerId).build())
            .healthProfile(HealthProfileDTO.empty())
            .behaviorPreferences(BehaviorPreferencesDTO.builder().build())
            .globalMemories(List.of())
            .build();
    }
}
