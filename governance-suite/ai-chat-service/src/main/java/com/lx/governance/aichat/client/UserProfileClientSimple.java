package com.lx.governance.aichat.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.UserProfileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * UserProfileClient - Simplified RabbitMQ RPC client using convertSendAndReceive
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileClientSimple {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${user.profile.request.timeout:5000}")
    private long requestTimeout;

    private static final String USER_PROFILE_EXCHANGE = "user.profile.exchange";
    private static final String USER_PROFILE_REQUEST_ROUTING_KEY = "user.profile.request";

    /**
     * Request user profile from user-profile-service
     *
     * @param customerId customer ID
     * @return UserProfileDTO
     */
    public UserProfileDTO requestUserProfile(String customerId) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("Sending user profile request: customerId={}", customerId);

            // Build request
            Map<String, Object> request = new HashMap<>();
            request.put("customerId", customerId);
            request.put("timestamp", System.currentTimeMillis());

            // Send and receive (RabbitTemplate handles correlation ID and reply-to automatically)
            rabbitTemplate.setReplyTimeout(requestTimeout);

            Object response = rabbitTemplate.convertSendAndReceive(
                USER_PROFILE_EXCHANGE,
                USER_PROFILE_REQUEST_ROUTING_KEY,
                request
            );

            if (response == null) {
                long duration = System.currentTimeMillis() - startTime;
                log.error("User profile request timeout: customerId={}, timeout={}ms, duration={}ms",
                        customerId, requestTimeout, duration);
                throw new RuntimeException("User profile request timeout after " + requestTimeout + "ms");
            }

            // Convert response
            UserProfileDTO profile = objectMapper.convertValue(response, UserProfileDTO.class);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Received user profile response: customerId={}, duration={}ms", customerId, duration);

            return profile;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("User profile request failed: customerId={}, duration={}ms", customerId, duration, e);
            throw new RuntimeException("Failed to request user profile: " + e.getMessage(), e);
        }
    }
}
