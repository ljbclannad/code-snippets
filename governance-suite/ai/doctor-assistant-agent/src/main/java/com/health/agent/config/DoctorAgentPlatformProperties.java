package com.health.agent.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * doctor 场景接入平台公共层时的可配置参数。
 */
@Data
@Component
@ConfigurationProperties(prefix = "doctor.agent.platform")
public class DoctorAgentPlatformProperties {

    private String workflowId = "doctor-patient-task-v1";

    private String platformAgentId = "doctor-assistant-agent";

    private String toolVersion = "doctor-toolkit-v1";

    private boolean fallbackToTemplateOnAgentError = true;

    /**
     * 允许按 sub-agent id 覆盖默认工具白名单。
     */
    private Map<String, List<String>> allowedTools = new LinkedHashMap<>();

    public List<String> resolveAllowedTools(String subAgentId, List<String> defaults) {
        List<String> configured = allowedTools.get(subAgentId);
        if (configured == null || configured.isEmpty()) {
            return defaults != null ? new ArrayList<>(defaults) : List.of();
        }
        return new ArrayList<>(configured);
    }
}
