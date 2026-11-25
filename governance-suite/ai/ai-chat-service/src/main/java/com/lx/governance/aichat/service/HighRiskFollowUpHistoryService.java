package com.lx.governance.aichat.service;

import com.lx.governance.aichat.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/** 随访问答在返回前落库；同轮重试更新原记录，不依赖回复缓存去重。 */
@Service
@RequiredArgsConstructor
public class HighRiskFollowUpHistoryService {
    private final JdbcTemplate jdbc;

    public long record(ChatRequest request, String reply, boolean confirmed) {
        String requestId = request.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        String key = turnKey(request.getCustomerId(), request.getConversationId(), requestId);
        long receivedAt = request.getMessageTimestamp() != null ? request.getMessageTimestamp() : System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO t_ai_chat_history
                  (customer_id, conversation_id, user_message, ai_response, create_time, is_deleted,
                   follow_up_key, follow_up_confirmed, follow_up_received_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  ai_response = CASE WHEN follow_up_confirmed = 0 OR ? = 1
                    THEN ? ELSE ai_response END,
                  follow_up_confirmed = GREATEST(follow_up_confirmed, ?)
                """, request.getCustomerId(), request.getConversationId(), request.getMessage(), reply,
                LocalDateTime.now(), key, confirmed ? 1 : 0, receivedAt, confirmed ? 1 : 0, reply, confirmed ? 1 : 0);
        // 重试沿用首次接收顺序，避免旧轮次在重试时覆盖更新的会话状态。
        return jdbc.queryForObject("SELECT follow_up_received_at FROM t_ai_chat_history WHERE follow_up_key = ?",
                Long.class, key);
    }

    private String turnKey(String customerId, String conversationId, String requestId) {
        String input = customerId.length() + ":" + customerId + conversationId.length() + ":" + conversationId
                + requestId.length() + ":" + requestId;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法计算随访历史去重键", ex);
        }
    }
}
