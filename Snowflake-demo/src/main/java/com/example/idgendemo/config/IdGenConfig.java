package com.example.idgendemo.config;

import com.example.idgendemo.id.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(IdGenProperties.class)
public class IdGenConfig {

    @Bean
    public IdGenMetrics idGenMetrics(MeterRegistry registry) {
        return new IdGenMetrics(registry);
    }

    @Bean
    public RedisWorkerIdAllocator redisWorkerIdAllocator(IdGenProperties props, StringRedisTemplate redis) {
        if (!props.getRedisWorker().isEnabled())
            return null;
        return new RedisWorkerIdAllocator(
                redis,
                props.getRedisWorker().getMaxWorkerId(),
                Duration.ofSeconds(props.getRedisWorker().getLeaseTtlSeconds()));
    }

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(IdGenProperties props, IdGenMetrics metrics,
            StringRedisTemplate redis, JdbcTemplate jdbcTemplate) {
        long workerId = resolveWorkerId(props, redisWorkerIdAllocator(props, redis));
        return new SnowflakeIdGenerator(workerId, props.getSnowflake().getEpoch(),
                props.getSnowflake().getMaxBackwardMs(), metrics);
    }

    @Bean
    public SegmentDao segmentDao(JdbcTemplate jdbcTemplate, IdGenMetrics metrics) {
        return new SegmentDao(jdbcTemplate, metrics);
    }

    @Bean
    public SegmentIdGenerator segmentIdGenerator(IdGenProperties props, SegmentDao dao, IdGenMetrics metrics) {
        return new SegmentIdGenerator(
                dao,
                props.getSegment().getBizTag(),
                props.getSegment().getRefillThreshold(),
                props.getSegment().isAsyncPrefetch(),
                metrics);
    }

    @Bean
    public SwitchableIdGenerator switchableIdGenerator(IdGenProperties props, SnowflakeIdGenerator snowflake,
            SegmentIdGenerator segment) {
        SwitchableIdGenerator.StrategyConfig init = new SwitchableIdGenerator.StrategyConfig(
                props.getStrategy(),
                props.getGray().isEnabled(),
                props.getGray().getSnowflakePercent());
        return new SwitchableIdGenerator(snowflake, segment, init);
    }

    @Bean
    public IdGenerator idGenerator(SwitchableIdGenerator sw) {
        // 业务侧只依赖 IdGenerator，底层可切换
        return sw;
    }

    /**
     * workerId 解析：
     * 1) 若配置了固定 workerId（适合你 <5 实例，手动分配），直接用
     * 2) 否则通过 Redis 租约分配（Deployment 推荐）
     */
    private long resolveWorkerId(IdGenProperties props, RedisWorkerIdAllocator allocator) {
        String fixed = props.getWorkerId();
        if (fixed != null && !fixed.isBlank()) {
            long wid = Long.parseLong(fixed.trim());
            if (wid < 0 || wid > 1023)
                throw new IllegalArgumentException("fixed workerId must be 0..1023");
            return wid;
        }
        if (allocator == null) {
            throw new IllegalStateException("workerId not set and redis-worker is disabled");
        }
        return allocator.allocate();
    }

    /**
     * 续租：发现租约丢失属于灾难级风险（workerId 冲突 -> 重复ID）
     * 生产建议：租约丢失后让实例健康检查 fail，配合 k8s 摘流量/重启
     */
    @Configuration
    static class LeaseRenewScheduler {

        private final RedisWorkerIdAllocator allocator;
        private final IdGenMetrics metrics;

        LeaseRenewScheduler(RedisWorkerIdAllocator allocator, IdGenMetrics metrics) {
            this.allocator = allocator;
            this.metrics = metrics;
        }

        @Scheduled(fixedDelayString = "#{${idgen.redis-worker.renew-interval-seconds:10} * 1000}")
        public void renew() {
            if (allocator == null)
                return;
            allocator.renewOrThrow(metrics);
        }
    }

    @Bean
    public HealthIndicator idGenHealthIndicator(RedisWorkerIdAllocator allocator, SegmentIdGenerator segment,
            SwitchableIdGenerator sw) {
        return () -> {
            Map<String, Object> details = new HashMap<>();
            details.put("strategy", sw.strategyName());

            if (allocator != null) {
                details.put("workerId", allocator.getWorkerId());
                details.put("workerLeaseAlive", allocator.leaseAlive());
                details.put("lastRenewEpochMs", allocator.getLastRenewEpochMs());
            }

            details.put("segmentRemaining", segment.currentRemaining());

            boolean healthy = true;
            if (allocator != null && !allocator.leaseAlive())
                healthy = false;

            // segmentRemaining == -1 表示还没初始化，不算不健康
            return healthy ? Health.up().withDetails(details).build()
                    : Health.down().withDetails(details).build();
        };
    }

    @Bean
    public InfoContributor idGenInfoContributor(SnowflakeIdGenerator snowflake, SegmentIdGenerator segment,
            SwitchableIdGenerator sw) {
        return builder -> {
            Map<String, Object> map = new HashMap<>();
            map.put("strategy", sw.strategyName());
            map.put("snowflakeWorkerId", snowflake.getWorkerId());
            map.put("segmentRemaining", segment.currentRemaining());
            builder.withDetail("idgen", map);
        };
    }
}
