package com.lx.governance.aichat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.ChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatConversationAccessTest {
    private ChatConversationAccess access;
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    @BeforeEach void setup() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE ai_chat_conversation_owner (conversation_id VARCHAR(128) PRIMARY KEY, customer_id VARCHAR(64))");
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        when(redis.opsForValue()).thenReturn(values);
        access = new ChatConversationAccess(jdbc, redis, new ObjectMapper());
    }

    @Test void patientAndGuestCannotReuseAnotherOwnersConversation() {
        var first = request("c1");
        access.bind(first, patient("p1"));
        assertTrue(first.isVerifiedPatient());
        assertEquals("p1", first.getCustomerId());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> access.bind(request("c1"), patient("p2")))
                .getStatusCode().value());
        var guest = new MockHttpServletRequest();
        assertThrows(ResponseStatusException.class, () -> access.bind(request("c1"), guest));
        var anonymous = request("c2");
        access.bind(anonymous, guest);
        assertFalse(anonymous.isVerifiedPatient());
        access.bind(request("c2"), guest);
        assertThrows(ResponseStatusException.class, () -> access.bind(request("c2"), new MockHttpServletRequest()));
        assertThrows(ResponseStatusException.class, () -> access.bind(request("c2"), patient("p1")));
        var forged = request("c3");
        forged.setCustomerId("p1");
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> access.bind(forged, guest)).getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> access.bind(forged, patient("p2")));
    }

    @Test void legacyRedisOwnerAndSimultaneousFirstClaimsAreProtected() throws Exception {
        when(values.get("ai:session:context:old")).thenReturn("{\"customerId\":\"p1\"}");
        assertThrows(ResponseStatusException.class, () -> access.bind(request("old"), patient("p2")));
        access.bind(request("old"), patient("p1"));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> claim = () -> {
                start.await();
                try {
                    access.bind(request("race"), patient(UUID.randomUUID().toString()));
                    return true;
                } catch (ResponseStatusException ex) {
                    assertEquals(403, ex.getStatusCode().value());
                    return false;
                }
            };
            var first = pool.submit(claim);
            var second = pool.submit(claim);
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static MockHttpServletRequest patient(String id) {
        var caller = new MockHttpServletRequest();
        caller.setUserPrincipal(() -> id);
        caller.addUserRole("PATIENT");
        return caller;
    }

    private static ChatRequest request(String id) {
        var request = new ChatRequest();
        request.setConversationId(id);
        return request;
    }
}
