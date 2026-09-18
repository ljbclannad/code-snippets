package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;

class ChatRequestIdempotencyServiceTest {
    private ChatRequestIdempotencyService service;
    private ValueOperations<String, String> values;
    private final ChatRequest request = new ChatRequest();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new ChatRequestIdempotencyService(redis, mapper);
        ReflectionTestUtils.setField(service, "responseTtlMinutes", 10L);
        request.setRequestId("request-1");
    }

    @Test
    void immediatelyCompletedSuccessShouldBeCachedAndReleased() throws Exception {
        ChatResponse response = ChatResponse.success("完成", "conversation");
        assertSame(response, service.executeOrReplay("conversation", request,
                () -> CompletableFuture.completedFuture(response)).join());
        assertFalse(service.isInFlight("conversation", "request-1"));
        verify(values).set(anyString(), eq(mapper.writeValueAsString(response)), eq(Duration.ofMinutes(10)));
    }

    @Test
    void immediatelyCompletedBusinessFailureShouldAllowRetry() {
        ChatResponse response = new ChatResponse();
        response.setSuccess(false);
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            assertSame(response, service.executeOrReplay("conversation", request, () -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(response);
            }).join());
            assertFalse(service.isInFlight("conversation", "request-1"));
        }
        assertEquals(2, calls.get());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void immediatelyFailedFutureShouldPreserveCauseAndAllowRetry() {
        RuntimeException failure = new IllegalStateException("上游失败");
        for (int i = 0; i < 2; i++) {
            CompletionException error = assertThrows(CompletionException.class,
                    () -> service.executeOrReplay("conversation", request,
                            () -> CompletableFuture.failedFuture(failure)).join());
            assertSame(failure, error.getCause());
            assertFalse(service.isInFlight("conversation", "request-1"));
        }
    }

    @Test
    void synchronousSupplierFailureShouldReleaseRequest() {
        RuntimeException failure = new IllegalStateException("启动失败");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.executeOrReplay("conversation", request, () -> { throw failure; })));
        assertFalse(service.isInFlight("conversation", "request-1"));
        assertNotNull(service.executeOrReplay("conversation", request,
                () -> CompletableFuture.completedFuture(new ChatResponse())).join());
    }

    @Test
    void overlappingRequestsShouldShareExecutionAndCacheBeforeRelease() {
        CompletableFuture<ChatResponse> upstream = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<ChatResponse> first = service.executeOrReplay("conversation", request, () -> {
            calls.incrementAndGet();
            return upstream;
        });
        CompletableFuture<ChatResponse> second = service.executeOrReplay("conversation", request, () -> {
            fail("重复请求不应执行上游");
            return upstream;
        });
        assertSame(first, second);
        doAnswer(invocation -> {
            assertTrue(service.isInFlight("conversation", "request-1"));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        ChatResponse response = ChatResponse.success("完成", "conversation");
        upstream.complete(response);
        assertSame(response, first.join());
        assertSame(response, second.join());
        assertEquals(1, calls.get());
        assertFalse(service.isInFlight("conversation", "request-1"));
    }

    @Test
    void cachedResponseShouldSkipSupplier() throws Exception {
        ChatResponse response = ChatResponse.success("缓存回复", "conversation");
        when(values.get(anyString())).thenReturn(mapper.writeValueAsString(response));
        assertEquals(response, service.executeOrReplay("conversation", request, () -> {
            fail("缓存命中不应执行上游");
            return null;
        }).join());
    }

    @Test
    void cacheCompletedBetweenLookupAndRegistrationShouldSkipSupplier() throws Exception {
        ChatResponse response = ChatResponse.success("缓存回复", "conversation");
        when(values.get(anyString())).thenReturn(null, mapper.writeValueAsString(response));
        assertEquals(response, service.executeOrReplay("conversation", request, () -> {
            fail("登记后应复查已完成缓存");
            return null;
        }).join());
        assertFalse(service.isInFlight("conversation", "request-1"));
    }
}
