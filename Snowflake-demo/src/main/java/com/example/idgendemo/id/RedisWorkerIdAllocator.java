package com.example.idgendemo.id;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Deployment 场景推荐：workerId 通过 Redis 租约抢占 + 续租。
 * - maxWorkerId 你只有 <5 实例，设置 32 足够。
 * - lease TTL 到期自动回收，避免“死节点占坑”
 *
 * 关键原则：发现租约丢失要立刻告警 + 摘流量（否则可能 workerId 冲突 -> 重复ID）
 */
public class RedisWorkerIdAllocator {

    private final StringRedisTemplate redis;
    private final int maxWorkerId;
    private final Duration leaseTtl;
    private final String instanceId = UUID.randomUUID().toString();

    private volatile long workerId = -1;
    private volatile long lastRenewEpochMs = -1;

    public RedisWorkerIdAllocator(StringRedisTemplate redis, int maxWorkerId, Duration leaseTtl) {
        this.redis = redis;
        this.maxWorkerId = maxWorkerId;
        this.leaseTtl = leaseTtl;
    }

    public long allocate() {
        if (workerId >= 0) return workerId;

        for (int i = 0; i < maxWorkerId * 3; i++) {
            long candidate = ThreadLocalRandom.current().nextInt(maxWorkerId);
            String key = leaseKey(candidate);
            Boolean ok = redis.opsForValue().setIfAbsent(key, instanceId, leaseTtl);
            if (Boolean.TRUE.equals(ok)) {
                this.workerId = candidate;
                this.lastRenewEpochMs = System.currentTimeMillis();
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot allocate workerId from redis");
    }

    public void renewOrThrow(IdGenMetrics metrics) {
        long wid = workerId;
        if (wid < 0) return;

        String key = leaseKey(wid);
        String val = redis.opsForValue().get(key);

        if (!instanceId.equals(val)) {
            metrics.workerLeaseLost.increment();
            throw new IllegalStateException("workerId lease lost: wid=" + wid + ", instanceId=" + instanceId + ", redisVal=" + val);
        }
        redis.expire(key, leaseTtl);
        lastRenewEpochMs = System.currentTimeMillis();
    }

    public boolean leaseAlive() {
        long wid = workerId;
        if (wid < 0) return false;
        String val = redis.opsForValue().get(leaseKey(wid));
        return instanceId.equals(val);
    }

    public long getWorkerId() { return workerId; }
    public long getLastRenewEpochMs() { return lastRenewEpochMs; }
    public String getInstanceId() { return instanceId; }

    private String leaseKey(long wid) {
        return "idgen:worker:lease:" + wid;
    }
}
