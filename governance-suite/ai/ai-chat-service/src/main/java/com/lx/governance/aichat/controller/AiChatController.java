package com.lx.governance.aichat.controller;

import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.service.AiChatService;
import jakarta.validation.Valid;
import com.lx.governance.aichat.service.ChatConversationAccess;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * AI Chat REST Controller
 */
@RestController
@RequestMapping("/api/chat")
@Slf4j
@RequiredArgsConstructor
public class AiChatController {

    private final ChatConversationAccess access;
    private final AiChatService aiChatService;

    /**
     * Handle chat request
     */
    @PostMapping
    public CompletableFuture<ResponseEntity<ChatResponse>> chat(@RequestBody @Valid ChatRequest request, HttpServletRequest caller) {
        access.bind(request, caller);
        log.info("收到聊天请求 - groupId: {}", request.getGroupId());

        try {
            return aiChatService.handleChatRequest(request)
                    .thenApply(response -> response.getSuccess()
                            ? ResponseEntity.ok(response)
                            : ResponseEntity.status(500).body(response))
                    .exceptionally(ex -> {
                        log.error("聊天请求处理异常 - customerId: {}", request.getCustomerId(), ex);
                        ChatResponse errorResponse = ChatResponse.error("本轮对话未能完成，请稍后再试。");
                        return ResponseEntity.status(500).body(errorResponse);
                    });
        } catch (Exception e) {
            log.error("聊天请求处理异常 - customerId: {}", request.getCustomerId(), e);
            ChatResponse errorResponse = ChatResponse.error("本轮对话未能完成，请稍后再试。");
            return CompletableFuture.completedFuture(ResponseEntity.status(500).body(errorResponse));
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }


}
