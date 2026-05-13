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
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

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

    @Value("${user.profile.request.timeout:5000}")
    private long requestTimeout;

    private final Map<String, CompletableFuture<UserProfileDTO>> pendingRequests = new ConcurrentHashMap<>();

    private static final String USER_PROFILE_EXCHANGE = "user.profile.exchange";
    private static final String USER_PROFILE_REQUEST_ROUTING_KEY = "user.profile.request";

    /**
     * Request user profile from user-profile-service
     *
     * @param userId user ID
     * @return UserProfileDTO
     */
    public UserProfileDTO requestUserProfile(Long userId) {
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<UserProfileDTO> future = new CompletableFuture<>();

        try {
            // Register pending request
            pendingRequests.put(correlationId, future);

            // Build request message
            MessageProperties properties = new MessageProperties();
            properties.setCorrelationId(correlationId);
            properties.setReplyTo(userProfileResponseQueue().getName());

            String requestBody = String.valueOf(userId);
            Message message = new Message(requestBody.getBytes(), properties);

            log.info("Sending user profile request: userId={}, correlationId={}", userId, correlationId);

            // Send request
            rabbitTemplate.send(USER_PROFILE_EXCHANGE, USER_PROFILE_REQUEST_ROUTING_KEY, message);

            // Wait for response with timeout
            UserProfileDTO profile = future.get(requestTimeout, TimeUnit.MILLISECONDS);
            log.info("Received user profile response: userId={}, correlationId={}", userId, correlationId);
            return profile;

        } catch (TimeoutException e) {
            log.error("User profile request timeout: userId={}, correlationId={}, timeout={}ms",
                     userId, correlationId, requestTimeout);
            pendingRequests.remove(correlationId);
            throw new RuntimeException("User profile request timeout after " + requestTimeout + "ms", e);

        } catch (Exception e) {
            log.error("User profile request failed: userId={}, correlationId={}", userId, correlationId, e);
            pendingRequests.remove(correlationId);
            throw new RuntimeException("Failed to request user profile", e);
        }
    }

    /**
     * Handle response from user-profile-service
     */
    @RabbitListener(queues = "#{userProfileResponseQueue.name}")
    public void handleResponse(Message message) {
        String correlationId = message.getMessageProperties().getCorrelationId();

        if (correlationId == null) {
            log.warn("Received response without correlationId");
            return;
        }

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
     * Create temporary response queue with unique name
     */
    @Bean
    public Queue userProfileResponseQueue() {
        String queueName = "user.profile.response." + UUID.randomUUID().toString();
        return new Queue(queueName, true, false, true);
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
