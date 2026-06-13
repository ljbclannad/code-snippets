package com.lx.governance.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorPreferencesDTO {
    private String dietPreference;
    private String exerciseFrequency;
    private Integer checkInStreak;
}
