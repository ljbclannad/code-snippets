package com.health.agent.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 聊天服务读取内部分析流和审批进度时共用服务身份。 */
@Component
public class PatientTaskServiceAuth {
    private final String expected;

    public PatientTaskServiceAuth(@Value("${agent.follow-up-reminder.service-token:}") String expected) {
        this.expected = expected;
    }

    void require(String token) {
        if (expected.isBlank() || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "服务身份校验失败");
        }
    }
}
