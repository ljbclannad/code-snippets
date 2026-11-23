package com.health.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "agent.follow-up-reminder")
public class FollowUpReminderProperties {
    private final Map<String, String> doctors = new HashMap<>();

    public String assignedDoctor(String customerId) {
        String doctor = doctors.get(customerId);
        return doctor == null || doctor.isBlank() ? null : doctor;
    }

    public Map<String, String> getDoctors() {
        return doctors;
    }
}
