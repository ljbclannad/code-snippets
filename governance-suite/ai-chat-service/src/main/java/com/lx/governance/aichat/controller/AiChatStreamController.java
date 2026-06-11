package com.lx.governance.aichat.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.StreamEvent;
import com.lx.governance.aichat.service.AiChatStreamService;

import jakarta.validation.Valid;
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

    private final AiChatStreamService streamService;
    private final ObjectMapper objectMapper;

    /**
     * 流式对话接口
     * 使用 Server-Sent Events (SSE) 返回流式响应
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody @Valid ChatRequest request) {
        log.info("收到流式聊天请求 - customerId: {}, groupId: {}, message: {}",
                request.getCustomerId(),
                request.getGroupId(),
                truncate(request.getMessage(), 50));

        return streamService.handleChatStreamRequest(request)
                .map(event -> {
                    try {
                        // 转换为 SSE 格式：data: {...}\n\n
                        return "data: " + objectMapper.writeValueAsString(event) + "\n\n";
                    } catch (Exception e) {
                        log.error("序列化流事件失败", e);
                        return "data: " + createErrorEvent(e.getMessage()) + "\n\n";
                    }
                })
                .doOnComplete(() -> log.info("流式响应完成 - customerId: {}", request.getCustomerId()))
                .doOnError(ex -> log.error("流式响应异常 - customerId: {}", request.getCustomerId(), ex));
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private String createErrorEvent(String errorMessage) {
        try {
            StreamEvent errorEvent = StreamEvent.error(errorMessage, 3001);
            return objectMapper.writeValueAsString(errorEvent);
        } catch (Exception e) {
            return "{\"type\":\"ERROR\",\"error\":\"系统错误\"}";
        }
    }
}
