package com.lx.governance.aichat.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.lx.governance.aichat.dto.ChatRequest;

/**
 * 为缺少 customerId 的请求补齐匿名访客身份。
 */
@Service
public class VisitorIdentityService {

    private static final String VISITOR_PREFIX = "visitor-";

    public String ensureCustomerId(ChatRequest request) {
        if (request == null) {
            return null;
        }
        if (request.getCustomerId() != null && !request.getCustomerId().isBlank()) {
            return request.getCustomerId();
        }

        String visitorId = VISITOR_PREFIX + UUID.randomUUID();
        request.setCustomerId(visitorId);
        return visitorId;
    }

    public boolean isVisitor(String customerId) {
        return customerId != null && customerId.startsWith(VISITOR_PREFIX);
    }
}
