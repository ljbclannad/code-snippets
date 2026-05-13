package com.lx.governance.aichat.client;

import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.DifyRequest;
import com.lx.governance.aichat.dto.DifyResponse;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.UserProfileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dify API client for AI chat integration
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DifyClient {

    private final RestClient difyRestClient;

    /**
     * Send chat request to Dify API
     *
     * @param request     chat request
     * @param userProfile user profile (nullable)
     * @param memories    memory items (nullable)
     * @return Dify API response
     */
    public DifyResponse chat(ChatRequest request, UserProfileDTO userProfile, List<MemoryItemDTO> memories) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("Calling Dify API for customer: {}, conversationId: {}",
                    request.getCustomerId(), request.getConversationId());

            DifyRequest difyRequest = buildDifyRequest(request, userProfile, memories);

            DifyResponse response = difyRestClient.post()
                    .uri("/chat-messages")
                    .body(difyRequest)
                    .retrieve()
                    .body(DifyResponse.class);

            long duration = System.currentTimeMillis() - startTime;
            log.info("Dify API call completed in {}ms, conversationId: {}, messageId: {}",
                    duration, response.getConversationId(), response.getMessageId());

            return response;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Dify API call failed after {}ms for customer: {}",
                    duration, request.getCustomerId(), e);
            throw new RuntimeException("Failed to call Dify API: " + e.getMessage(), e);
        }
    }

    /**
     * Build Dify API request with user profile and memories
     *
     * @param request     chat request
     * @param userProfile user profile (nullable)
     * @param memories    memory items (nullable)
     * @return Dify request DTO
     */
    private DifyRequest buildDifyRequest(ChatRequest request, UserProfileDTO userProfile, List<MemoryItemDTO> memories) {
        Map<String, Object> inputs = new HashMap<>();

        // Add user profile if available
        if (userProfile != null) {
            inputs.put("user_profile", userProfile);
        }

        // Add memories if available (extract content list)
        if (memories != null && !memories.isEmpty()) {
            List<String> memoryContents = memories.stream()
                    .map(MemoryItemDTO::getContent)
                    .collect(Collectors.toList());
            inputs.put("memories", memoryContents);
        }

        return DifyRequest.builder()
                .query(request.getMessage())
                .user(request.getCustomerId())
                .conversationId(request.getConversationId())
                .inputs(inputs)
                .responseMode(false)
                .build();
    }
}
