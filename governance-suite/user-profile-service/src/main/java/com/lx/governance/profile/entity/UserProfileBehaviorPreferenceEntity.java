package com.lx.governance.profile.entity;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class UserProfileBehaviorPreferenceEntity {

    private Long id;
    private String customerId;
    private String dietPreference;
    private String exerciseFrequency;
    private Integer checkInStreak;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
