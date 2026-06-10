package com.health.agent.controller;

import com.health.agent.exception.AgentException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentAccessGuardTest {

    @Test
    void shouldRejectWhenSseConnectionsReachLimit() {
        AgentAccessGuard guard = new AgentAccessGuard();
        ReflectionTestUtils.setField(guard, "maxSseConnections", 1);
        ReflectionTestUtils.setField(guard, "maxRequestsPerMinute", 100);

        AgentAccessGuard.AccessPermit first = guard.acquire("D001", true);

        AgentException exception = assertThrows(
            AgentException.class,
            () -> guard.acquire("D002", true)
        );

        assertEquals("SSE_CONNECTION_LIMIT: 当前流式连接数已达到上限", exception.getMessage());

        first.release();
    }

    @Test
    void shouldReleaseSseConnectionOnlyOnce() {
        AgentAccessGuard guard = new AgentAccessGuard();
        ReflectionTestUtils.setField(guard, "maxSseConnections", 1);
        ReflectionTestUtils.setField(guard, "maxRequestsPerMinute", 100);

        AgentAccessGuard.AccessPermit permit = guard.acquire("D001", true);
        permit.release();
        permit.release();

        AgentAccessGuard.AccessPermit second = guard.acquire("D002", true);
        second.release();
    }

    @Test
    void shouldRateLimitPerDoctorWithinOneMinuteWindow() {
        AgentAccessGuard guard = new AgentAccessGuard();
        ReflectionTestUtils.setField(guard, "maxSseConnections", 10);
        ReflectionTestUtils.setField(guard, "maxRequestsPerMinute", 1);

        guard.acquire("D001", false).release();

        AgentException exception = assertThrows(
            AgentException.class,
            () -> guard.acquire("D001", false)
        );

        assertEquals("RATE_LIMITED: 当前医生请求过于频繁，请稍后重试", exception.getMessage());
    }
}
