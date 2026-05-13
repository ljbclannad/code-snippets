package com.lx.governance.aichat.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * User profile DTO (reused from user-profile-service)
 */
@Data
public class UserProfileDTO {

    private Map<String, Object> basicInfo;
    private Map<String, Object> healthProfile;
    private Map<String, Object> behaviorPreferences;
    private List<Map<String, Object>> globalMemories;
}
