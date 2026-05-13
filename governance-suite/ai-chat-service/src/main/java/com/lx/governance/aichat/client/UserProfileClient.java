package com.lx.governance.aichat.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.UserProfileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * UserProfileClient - RabbitMQ request-response client for user profile service
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileClient {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final Queue userProfileResponseQueue;

    @Value("${user.profile.request.timeout:5000}")
    private long requestTimeout;

    private final Map<String, CompletableFuture<UserProfileDTO>> pendingRequests = new ConcurrentHashMap<>();

    private static final String USER_PROFILE_EXCHANGE = "user.profile.exchange";
    private static final String USER_PROFILE_REQUEST_ROUTING_KEY = "user.profile.request";

    /**
     * Request user profile from user-profile-service
     *
     * @param customerId customer ID
     * @return UserProfileDTO
     */
    public UserProfileDTO requestUserProfile(String customerId) {
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<UserProfileDTO> future = new CompletableFuture<>();

        try {
            // Register pending request
            pendingRequests.put(correlationId, future);

            // Build request message object
            Map<String, Object> requestPayload = new HashMap<>();
            requestPayload.put("correlationId", correlationId);
            requestPayload.put("customerId", customerId);

            // Build message properties
            MessageProperties properties = new MessageProperties();
            properties.setCorrelationId(correlationId);
            properties.setReplyTo(userProfileResponseQueue.getName());

            log.info("Sending user profile request: customerId={}, correlationId={}, replyTo={}",
                    customerId, correlationId, userProfileResponseQueue.getName());

            // Send request using convertAndSend (will use JSON converter)
            rabbitTemplate.convertAndSend(USER_PROFILE_EXCHANGE, USER_PROFILE_REQUEST_ROUTING_KEY,
                    requestPayload, message -> {
                        message.getMessageProperties().setCorrelationId(correlationId);
                        message.getMessageProperties().setReplyTo(userProfileResponseQueue.getName());
                        return message;
                    });

            // Wait for response with timeout
            UserProfileDTO profile = future.get(requestTimeout, TimeUnit.MILLISECONDS);
            log.info("Received user profile response: customerId={}, correlationId={}", customerId, correlationId);
            return profile;

        } catch (TimeoutException e) {
            log.error("User profile request timeout: customerId={}, correlationId={}, timeout={}ms",
                     customerId, correlationId, requestTimeout);
            pendingRequests.remove(correlationId);
            throw new RuntimeException("User profile request timeout after " + requestTimeout + "ms", e);

        } catch (Exception e) {
            log.error("User profile request failed: customerId={}, correlationId={}", customerId, correlationId, e);
            pendingRequests.remove(correlationId);
            throw new RuntimeException("Failed to request user profile", e);
        }
    }

    /**
     * Handle response from user-profile-service
     */
    @RabbitListener(queues = "#{userProfileResponseQueue.name}")
    public void handleResponse(Message message) {
        log.info("Received message in handleResponse");
        String correlationId = message.getMessageProperties().getCorrelationId();

        if (correlationId == null) {
            log.warn("Received response without correlationId");
            return;
        }

        log.info("Processing response for correlationId={}", correlationId);

        CompletableFuture<UserProfileDTO> future = pendingRequests.remove(correlationId);

        if (future == null) {
            log.warn("No pending request found for correlationId={}", correlationId);
            return;
        }

        try {
            String responseBody = new String(message.getBody());
            log.debug("Received user profile response: correlationId={}, body={}", correlationId, responseBody);

            UserProfileDTO profile = convertToUserProfile(responseBody);
            future.complete(profile);

        } catch (Exception e) {
            log.error("Failed to parse user profile response: correlationId={}", correlationId, e);
            future.completeExceptionally(e);
        }
    }

    /**
     * Convert response JSON to UserProfileDTO
     */
    private UserProfileDTO convertToUserProfile(String json) {
        try {
            return objectMapper.readValue(json, UserProfileDTO.class);
        } catch (Exception e) {
            log.error("Failed to convert JSON to UserProfileDTO: json={}", json, e);
            throw new RuntimeException("Failed to parse user profile response", e);
        }
    }
}
