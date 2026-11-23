package com.lx.governance.aichat.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.StreamEvent;
import com.lx.governance.aichat.service.AiChatStreamService;

import jakarta.validation.Valid;
import com.lx.governance.aichat.service.ChatConversationAccess;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * AI Chat 流式响应控制器
 */
@RestController
@RequestMapping("/api/chat")
@Slf4j
@RequiredArgsConstructor
public class AiChatStreamController {

    private final ChatConversationAccess access;
    private final AiChatStreamService streamService;


    /**
     * 流式对话接口
     * 使用 Server-Sent Events (SSE) 返回流式响应
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamEvent> chatStream(@RequestBody @Valid ChatRequest request, HttpServletRequest caller) {
        access.bind(request, caller);
        log.info("收到聊天请求 - groupId: {}", request.getGroupId());

        return streamService.handleChatStreamRequest(request)
                .doOnComplete(() -> log.info("流式响应完成 - customerId: {}", request.getCustomerId()))
                .doOnError(ex -> log.error("流式响应异常 - customerId: {}", request.getCustomerId(), ex));
    }
}
