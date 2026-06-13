package com.lx.governance.aichat.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lx.governance.aichat.dto.ConversationHistoryResponseDTO;
import com.lx.governance.aichat.dto.ConversationSummaryDTO;
import com.lx.governance.aichat.service.ChatHistoryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/chat/conversations")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    @GetMapping
    public ResponseEntity<List<ConversationSummaryDTO>> listConversations(
            @RequestParam String customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(chatHistoryService.listConversations(customerId, page, size));
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<ConversationHistoryResponseDTO> getConversationHistory(
            @RequestParam String customerId,
            @PathVariable String conversationId) {
        return ResponseEntity.ok(chatHistoryService.getConversationHistory(customerId, conversationId));
    }
}
