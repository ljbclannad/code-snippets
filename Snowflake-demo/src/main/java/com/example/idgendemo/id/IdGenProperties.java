package com.example.idgendemo.id;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "idgen")
public class IdGenProperties {

    private Strategy strategy = Strategy.SNOWFLAKE;
    private String workerId; // optional fixed workerId

    private RedisWorker redisWorker = new RedisWorker();
    private Snowflake snowflake = new Snowflake();
    private Segment segment = new Segment();
    private Gray gray = new Gray();

    public enum Strategy { SNOWFLAKE, SEGMENT }

    public Strategy getStrategy() { return strategy; }
    public void setStrategy(Strategy strategy) { this.strategy = strategy; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public RedisWorker getRedisWorker() { return redisWorker; }
    public void setRedisWorker(RedisWorker redisWorker) { this.redisWorker = redisWorker; }

    public Snowflake getSnowflake() { return snowflake; }
    public void setSnowflake(Snowflake snowflake) { this.snowflake = snowflake; }

    public Segment getSegment() { return segment; }
    public void setSegment(Segment segment) { this.segment = segment; }

    public Gray getGray() { return gray; }
    public void setGray(Gray gray) { this.gray = gray; }

    public static class RedisWorker {
        private boolean enabled = true;
        private int maxWorkerId = 32;
        private int leaseTtlSeconds = 30;
        private int renewIntervalSeconds = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getMaxWorkerId() { return maxWorkerId; }
        public void setMaxWorkerId(int maxWorkerId) { this.maxWorkerId = maxWorkerId; }

        public int getLeaseTtlSeconds() { return leaseTtlSeconds; }
        public void setLeaseTtlSeconds(int leaseTtlSeconds) { this.leaseTtlSeconds = leaseTtlSeconds; }

        public int getRenewIntervalSeconds() { return renewIntervalSeconds; }
        public void setRenewIntervalSeconds(int renewIntervalSeconds) { this.renewIntervalSeconds = renewIntervalSeconds; }
    }

    public static class Snowflake {
        private long epoch = 1704067200000L;
        private long maxBackwardMs = 5L;

        public long getEpoch() { return epoch; }
        public void setEpoch(long epoch) { this.epoch = epoch; }

        public long getMaxBackwardMs() { return maxBackwardMs; }
        public void setMaxBackwardMs(long maxBackwardMs) { this.maxBackwardMs = maxBackwardMs; }
    }

    public static class Segment {
        private String bizTag = "order";
        private long refillThreshold = 200;
        private boolean asyncPrefetch = true;

        public String getBizTag() { return bizTag; }
        public void setBizTag(String bizTag) { this.bizTag = bizTag; }

        public long getRefillThreshold() { return refillThreshold; }
        public void setRefillThreshold(long refillThreshold) { this.refillThreshold = refillThreshold; }

        public boolean isAsyncPrefetch() { return asyncPrefetch; }
        public void setAsyncPrefetch(boolean asyncPrefetch) { this.asyncPrefetch = asyncPrefetch; }
    }

    public static class Gray {
        private boolean enabled = false;
        private int snowflakePercent = 100;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getSnowflakePercent() { return snowflakePercent; }
        public void setSnowflakePercent(int snowflakePercent) { this.snowflakePercent = snowflakePercent; }
    }
}
