package com.lx.governance.aichat.controller;

import com.lx.governance.aichat.document.ChatHistoryDocument;
import com.lx.governance.aichat.service.ChatHistorySearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 对话搜索控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatSearchController {

    private final ChatHistorySearchService searchService;

    @Value("${ai-chat.chat-history.search-enabled:true}")
    private boolean searchEnabled;

    /**
     * 搜索对话历史
     *
     * @param customerId 客户ID
     * @param keyword 搜索关键词
     * @param page 页码，默认0
     * @param size 返回结果数量，默认10
     * @return 搜索结果列表
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchChatHistory(
            @RequestParam String customerId,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        if (!searchEnabled) {
            return ResponseEntity.ok()
                    .body(new ErrorResponse("搜索功能未启用"));
        }

        if (keyword == null || keyword.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("搜索关键词不能为空"));
        }

        if (size <= 0 || size > 100) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("返回结果数量必须在 1-100 之间"));
        }

        try {
            var results = searchService.searchHistory(customerId, keyword, page, size);

            log.info("搜索对话历史 - customerId: {}, keyword: {}, 结果数: {}",
                    customerId, keyword, results.getTotalElements());

            return ResponseEntity.ok(new SearchResponse(
                    results.getContent(),
                    (int) results.getTotalElements()));

        } catch (Exception e) {
            log.error("搜索对话历史失败 - customerId: {}, keyword: {}",
                    customerId, keyword, e);
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse("搜索失败: " + e.getMessage()));
        }
    }

    /**
     * 推荐相似对话
     *
     * @param customerId 客户ID
     * @param message 当前消息
     * @param size 返回结果数量，默认3
     * @return 相似对话列表
     */
    @GetMapping("/recommend")
    public ResponseEntity<?> recommendSimilarConversations(
            @RequestParam String customerId,
            @RequestParam String message,
            @RequestParam(defaultValue = "3") int size) {

        if (!searchEnabled) {
            return ResponseEntity.ok()
                    .body(new ErrorResponse("推荐功能未启用"));
        }

        if (message == null || message.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("消息内容不能为空"));
        }

        if (size <= 0 || size > 10) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("返回结果数量必须在 1-10 之间"));
        }

        try {
            List<ChatHistoryDocument> results = searchService.findSimilarConversations(
                    customerId, message, size);

            log.info("推荐相似对话 - customerId: {}, 结果数: {}",
                    customerId, results.size());

            return ResponseEntity.ok(new SearchResponse(results, results.size()));

        } catch (Exception e) {
            log.error("推荐相似对话失败 - customerId: {}, message: {}",
                    customerId, message, e);
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse("推荐失败: " + e.getMessage()));
        }
    }

    /**
     * 搜索响应
     */
    private record SearchResponse(List<ChatHistoryDocument> data, int total) {}

    /**
     * 错误响应
     */
    private record ErrorResponse(String error) {}
}
