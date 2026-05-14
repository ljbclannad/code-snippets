package com.lx.governance.aichat.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * VectorMemoryClient - RabbitMQ request-response client for vector memory service
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorMemoryClient {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final Queue vectorMemoryResponseQueue;

    @Value("${vector.memory.request.timeout:5000}")
    private long requestTimeout;

    private final Map<String, CompletableFuture<List<MemoryItemDTO>>> pendingRequests = new ConcurrentHashMap<>();

    private static final String VECTOR_MEMORY_EXCHANGE = "vector.memory.exchange";
    private static final String VECTOR_MEMORY_REQUEST_ROUTING_KEY = "vector.memory.request";
    private static final String MEMORY_EXTRACTION_EXCHANGE = "memory.extraction.exchange";
    private static final String MEMORY_EXTRACTION_ROUTING_KEY = "memory.extraction";

    /**
     * Request group memories from vector-memory-service
     *
     * @param groupId group ID
     * @param customerId customer ID
     * @param query search query
     * @return List of MemoryItemDTO (empty list on timeout or error)
     */
    public List<MemoryItemDTO> requestGroupMemories(String groupId, String customerId, String query) {
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<List<MemoryItemDTO>> future = new CompletableFuture<>();

        try {
            // Register pending request
            pendingRequests.put(correlationId, future);

            // Build request payload
            Map<String, Object> requestPayload = new HashMap<>();
            requestPayload.put("correlationId", correlationId);
            requestPayload.put("groupId", groupId);
            requestPayload.put("customerId", customerId);
            requestPayload.put("query", query);
            requestPayload.put("topK", 5);
            requestPayload.put("timestamp", System.currentTimeMillis());

            String replyToQueue = vectorMemoryResponseQueue.getName();

            log.info("Sending vector memory request: groupId={}, customerId={}, query={}, correlationId={}, replyTo={}",
                    groupId, customerId, query, correlationId, replyToQueue);

            // Send request using convertAndSend (will use JSON converter)
            rabbitTemplate.convertAndSend(VECTOR_MEMORY_EXCHANGE, VECTOR_MEMORY_REQUEST_ROUTING_KEY,
                    requestPayload, message -> {
                        MessageProperties props = message.getMessageProperties();
                        props.setCorrelationId(correlationId);
                        props.setReplyTo(replyToQueue);
                        props.setContentType("application/json");
                        return message;
                    });

            // Wait for response with timeout
            List<MemoryItemDTO> memories = future.get(requestTimeout, TimeUnit.MILLISECONDS);
            log.info("Received vector memory response: groupId={}, customerId={}, count={}, correlationId={}",
                    groupId, customerId, memories.size(), correlationId);
            return memories;

        } catch (TimeoutException e) {
            log.error("Vector memory request timeout: groupId={}, customerId={}, correlationId={}, timeout={}ms",
                    groupId, customerId, correlationId, requestTimeout);
            pendingRequests.remove(correlationId);
            return Collections.emptyList(); // Return empty list on timeout

        } catch (Exception e) {
            log.error("Vector memory request failed: groupId={}, customerId={}, correlationId={}",
                    groupId, customerId, correlationId, e);
            pendingRequests.remove(correlationId);
            return Collections.emptyList(); // Return empty list on error
        }
    }

    /**
     * Trigger memory extraction asynchronously (fire-and-forget)
     *
     * @param groupId group ID
     * @param customerId customer ID
     * @param userMessage user message
     * @param aiResponse AI response
     */
    public void triggerMemoryExtraction(String groupId, String customerId, String userMessage, String aiResponse) {
        String correlationId = UUID.randomUUID().toString();

        try {
            // Build extraction request payload
            Map<String, Object> extractionPayload = new HashMap<>();
            extractionPayload.put("correlationId", correlationId);
            extractionPayload.put("groupId", groupId);
            extractionPayload.put("customerId", customerId);
            extractionPayload.put("userMessage", userMessage);
            extractionPayload.put("aiResponse", aiResponse);
            extractionPayload.put("timestamp", System.currentTimeMillis());

            // Build request message
            MessageProperties properties = new MessageProperties();
            properties.setCorrelationId(correlationId);
            properties.setContentType("application/json");

            String requestBody = objectMapper.writeValueAsString(extractionPayload);
            Message message = new Message(requestBody.getBytes(), properties);

            log.info("Triggering memory extraction: groupId={}, customerId={}, correlationId={}",
                    groupId, customerId, correlationId);

            // Send request (fire-and-forget, no response expected)
            rabbitTemplate.send(MEMORY_EXTRACTION_EXCHANGE, MEMORY_EXTRACTION_ROUTING_KEY, message);

        } catch (Exception e) {
            log.error("Failed to trigger memory extraction: groupId={}, customerId={}, correlationId={}",
                    groupId, customerId, correlationId, e);
            // Swallow exception - this is fire-and-forget
        }
    }

    /**
     * Handle response from vector-memory-service
     */
    @RabbitListener(queues = "#{vectorMemoryResponseQueue.name}")
    public void handleResponse(Message message) {
        String correlationId = message.getMessageProperties().getCorrelationId();

        if (correlationId == null) {
            log.warn("Received response without correlationId");
            return;
        }

        CompletableFuture<List<MemoryItemDTO>> future = pendingRequests.remove(correlationId);

        if (future == null) {
            log.warn("No pending request found for correlationId={}", correlationId);
            return;
        }

        try {
            String responseBody = new String(message.getBody());
            log.debug("Received vector memory response: correlationId={}, body={}", correlationId, responseBody);

            List<MemoryItemDTO> memories = convertToMemories(responseBody);
            future.complete(memories);

        } catch (Exception e) {
            log.error("Failed to parse vector memory response: correlationId={}", correlationId, e);
            future.completeExceptionally(e);
        }
    }

    /**
     * Convert response JSON to List<MemoryItemDTO>
     */
    private List<MemoryItemDTO> convertToMemories(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<MemoryItemDTO>>() {});
        } catch (Exception e) {
            log.error("Failed to convert JSON to List<MemoryItemDTO>: json={}", json, e);
            throw new RuntimeException("Failed to parse vector memory response", e);
        }
    }
}
