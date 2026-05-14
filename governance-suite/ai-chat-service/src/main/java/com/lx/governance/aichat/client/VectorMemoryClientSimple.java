package com.lx.governance.aichat.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * VectorMemoryClient - Simplified RabbitMQ RPC client using convertSendAndReceive
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorMemoryClientSimple {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Value("${vector.memory.request.timeout:5000}")
    private long requestTimeout;

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
        long startTime = System.currentTimeMillis();

        try {
            log.info("Sending vector memory request: groupId={}, customerId={}, query={}",
                    groupId, customerId, truncate(query, 50));

            // Build request
            Map<String, Object> request = new HashMap<>();
            request.put("groupId", groupId);
            request.put("customerId", customerId);
            request.put("query", query);
            request.put("topK", 5);
            request.put("timestamp", System.currentTimeMillis());

            // Send and receive
            rabbitTemplate.setReplyTimeout(requestTimeout);

            Object response = rabbitTemplate.convertSendAndReceive(
                VECTOR_MEMORY_EXCHANGE,
                VECTOR_MEMORY_REQUEST_ROUTING_KEY,
                request
            );

            if (response == null) {
                long duration = System.currentTimeMillis() - startTime;
                log.warn("Vector memory request timeout: groupId={}, customerId={}, timeout={}ms, duration={}ms",
                        groupId, customerId, requestTimeout, duration);
                return Collections.emptyList();
            }

            // Convert response
            List<MemoryItemDTO> memories = objectMapper.convertValue(
                response,
                new TypeReference<List<MemoryItemDTO>>() {}
            );

            long duration = System.currentTimeMillis() - startTime;
            log.info("Received vector memory response: groupId={}, customerId={}, count={}, duration={}ms",
                    groupId, customerId, memories.size(), duration);

            return memories;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Vector memory request failed: groupId={}, customerId={}, duration={}ms",
                    groupId, customerId, duration, e);
            return Collections.emptyList();
        }
    }

    /**
     * Trigger memory extraction (fire-and-forget)
     *
     * @param groupId group ID
     * @param customerId customer ID
     * @param userMessage user message
     * @param aiResponse AI response
     */
    public void triggerMemoryExtraction(String groupId, String customerId, String userMessage, String aiResponse) {
        try {
            log.info("Triggering memory extraction: groupId={}, customerId={}", groupId, customerId);

            Map<String, Object> request = new HashMap<>();
            request.put("correlationId", UUID.randomUUID().toString());
            request.put("groupId", groupId);
            request.put("customerId", customerId);
            request.put("userMessage", userMessage);
            request.put("aiResponse", aiResponse);
            request.put("timestamp", System.currentTimeMillis());

            rabbitTemplate.convertAndSend(
                MEMORY_EXTRACTION_EXCHANGE,
                MEMORY_EXTRACTION_ROUTING_KEY,
                request
            );

            log.info("Memory extraction triggered: groupId={}, customerId={}", groupId, customerId);

        } catch (Exception e) {
            log.error("Failed to trigger memory extraction: groupId={}, customerId={}",
                    groupId, customerId, e);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
