package com.lx.governance.aichat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.ChatRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatConversationAccess {
    private final JdbcTemplate jdbc;
    private final RedisTemplate<String, String> redis;
    private final ObjectMapper mapper;

    public void bind(ChatRequest request, HttpServletRequest caller) {
        String owner;
        if (caller.getUserPrincipal() == null && (request.getCustomerId() == null || request.getCustomerId().isBlank())) {
            owner = "visitor-" + UUID.nameUUIDFromBytes(caller.getSession().getId().getBytes(StandardCharsets.UTF_8));
            request.setVerifiedPatient(false);
        } else {
            owner = requirePatient(caller, request.getCustomerId());
            request.setVerifiedPatient(true);
        }
        request.setCustomerId(owner);
        request.setUserId(owner);
        boolean supplied = request.getConversationId() != null && !request.getConversationId().isBlank();
        if (!supplied) {
            UUID id = request.getRequestId() == null || request.getRequestId().isBlank() ? UUID.randomUUID()
                    : UUID.nameUUIDFromBytes((owner + "\n" + request.getRequestId()).getBytes(StandardCharsets.UTF_8));
            request.setConversationId(id.toString());
        }
        String conversation = request.getConversationId();
        if (conversation.length() > 128 || !conversation.equals(conversation.strip())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话编号格式无效");
        }
        var owners = owners(conversation);
        if (owners.isEmpty()) {
            if (supplied) {
                checkLegacyContext(conversation, owner);
            }
            jdbc.update("""
                    INSERT INTO ai_chat_conversation_owner (conversation_id, customer_id) VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE conversation_id = conversation_id
                    """, conversation, owner);
            owners = owners(conversation);
        }
        if (owners.size() != 1 || !owner.equals(owners.get(0))) {
            throw denied();
        }
    }

    private List<String> owners(String conversation) {
        return jdbc.queryForList("SELECT customer_id FROM ai_chat_conversation_owner WHERE conversation_id = ?",
                String.class, conversation);
    }

    public void requireOwnedConversation(String conversation, String owner) {
        if (conversation.isBlank() || conversation.length() > 128 || !conversation.equals(conversation.strip())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话编号格式无效");
        }
        var owners = owners(conversation);
        if (owners.size() != 1 || !owner.equals(owners.get(0))) {
            throw denied();
        }
    }

    private void checkLegacyContext(String conversation, String owner) {
        String json = redis.opsForValue().get("ai:session:context:" + conversation);
        if (json != null) {
            try {
                if (!owner.equals(mapper.readTree(json).path("customerId").asText())) {
                    throw denied();
                }
            } catch (JsonProcessingException ex) {
                throw denied();
            }
        }
    }

    public static String requirePatient(HttpServletRequest caller, String claimed) {
        if (caller.getUserPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录患者账号");
        }
        String subject = caller.getUserPrincipal().getName();
        if (!caller.isUserInRole("PATIENT") || subject == null || subject.isBlank() || subject.length() > 64
                || !subject.equals(subject.strip()) || subject.startsWith("visitor-")
                || claimed != null && !claimed.isBlank() && !subject.equals(claimed)) {
            throw denied();
        }
        return subject;
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问此患者或会话");
    }
}
