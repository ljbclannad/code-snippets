package com.lx.governance.aichat.quota;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import org.springframework.transaction.support.TransactionCallback;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuotaServiceConcurrencyTest {
    private final QuotaRepository accounts = new QuotaRepository();
    private final QuotaUsageRepository records = spy(new QuotaUsageRepository());
    private final RedisTemplate<String, Object> redis = mock(RedisTemplate.class);

    private QuotaService service() {
        var service = new QuotaService(redis, accounts, records, mock(TransactionTemplate.class));
        ReflectionTestUtils.setField(service, "quotaEnabled", true);
        return service;
    }

    private void seed() {
        accounts.save(UserQuota.builder().customerId("u").quotaType(QuotaType.TOTAL)
                .quotaLimit(10000).quotaUsed(100).enabled(true).build());
    }

    @Test
    void expiredPeriodAndExplicitResetUseCurrentAccount() {
        seed();
        var tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call -> call.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        var service = new QuotaService(redis, accounts, records, tx);
        ReflectionTestUtils.setField(service, "quotaEnabled", true);
        var expired = accounts.findByCustomerId("u");
        expired.setQuotaType(QuotaType.DAILY);
        expired.setResetTime(LocalDateTime.now().minusDays(1));
        accounts.update(expired);
        service.consumeQuota("u", "c", "new-period", 7, 3);
        assertEquals(10, service.getUserQuota("u").getQuotaUsed());
        assertEquals(0, service.resetQuota(expired).getQuotaUsed());
        assertTrue(service.getUserQuota("u").getResetTime().isAfter(LocalDateTime.now()));
    }

    @Test
    void concurrentChargesIgnoreStaleCacheAndMatchLedger() throws Exception {
        seed();
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(any())).thenAnswer(call -> accounts.findByCustomerId("u").toBuilder().quotaUsed(100).build());
        var first = service();
        var second = service();
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int message = i;
                jobs.add(pool.submit(() -> {
                    start.await();
                    (message % 2 == 0 ? first : second).consumeQuota("u", "c", "m" + message, 7, 3);
                    return null;
                }));
            }
            start.countDown();
            for (var job : jobs) {
                job.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1100, first.getUserQuota("u").getQuotaUsed());
        assertEquals(1000, records.findByCustomerId("u").stream().mapToInt(QuotaUsageRecord::getTotalTokens).sum());
        assertEquals(100, records.findByCustomerId("u").size());
    }

    @Test
    void duplicateSettlementAndCacheFailureDoNotChargeAgain() {
        seed();
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("模拟 Redis 不可用"));
        var service = service();
        service.consumeQuota("u", "c", "same", 7, 3);
        service.consumeQuota("u", "c", "same", 7, 3);
        assertEquals(110, service.getUserQuota("u").getQuotaUsed());
        assertEquals(1, records.findByCustomerId("u").size());
        var snapshot = service.getUserQuota("u");
        snapshot.setQuotaUsed(0);
        assertEquals(110, service.getUserQuota("u").getQuotaUsed());
    }

    @Test
    void failedLedgerWriteCanRetryWithoutLosingOrDuplicatingUsage() {
        seed();
        doThrow(new IllegalStateException("流水写入失败")).doCallRealMethod().when(records).save(any());
        var service = service();
        assertThrows(IllegalStateException.class, () -> service.consumeQuota("u", "c", "same", 7, 3));
        assertEquals(100, accounts.findByCustomerId("u").getQuotaUsed());
        service.consumeQuota("u", "c", "same", 7, 3);
        assertEquals(110, accounts.findByCustomerId("u").getQuotaUsed());
        assertEquals(1, records.findByCustomerId("u").size());
    }
}
