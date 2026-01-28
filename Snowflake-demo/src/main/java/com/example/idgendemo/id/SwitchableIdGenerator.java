package com.example.idgendemo.id;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 雪花/号段“一键切换” + 灰度分流。
 *
 * 说明：
 * 1) 主策略由 StrategyConfig.strategy 控制（SNOWFLAKE or SEGMENT）
 * 2) grayEnabled=true 时：
 *    - 默认主策略仍是 SNOWFLAKE（更高吞吐）
 *    - 通过 snowflakePercent 控制“走雪花”的比例；其余走号段
 *    - 分流 key（grayKey）建议用 userId/tenantId 保证稳定
 *
 * 关键：两种 ID 范围隔离（号段置 bit62），避免碰撞
 */
public class SwitchableIdGenerator implements IdGenerator {

    public static class StrategyConfig {
        public final IdGenProperties.Strategy strategy;
        public final boolean grayEnabled;
        public final int snowflakePercent;

        public StrategyConfig(IdGenProperties.Strategy strategy, boolean grayEnabled, int snowflakePercent) {
            this.strategy = Objects.requireNonNull(strategy);
            this.grayEnabled = grayEnabled;
            this.snowflakePercent = snowflakePercent;
        }
    }

    private final SnowflakeIdGenerator snowflake;
    private final SegmentIdGenerator segment;
    private final AtomicReference<StrategyConfig> configRef;

    public SwitchableIdGenerator(SnowflakeIdGenerator snowflake, SegmentIdGenerator segment, StrategyConfig initial) {
        this.snowflake = snowflake;
        this.segment = segment;
        this.configRef = new AtomicReference<>(initial);
    }

    @Override
    public long nextId(String grayKey) {
        StrategyConfig c = configRef.get();

        // 主策略是 SEGMENT：直接走号段（灰度此时通常不需要）
        if (c.strategy == IdGenProperties.Strategy.SEGMENT) {
            return segment.nextId(grayKey);
        }

        // 主策略是 SNOWFLAKE：可选灰度分流到 SEGMENT（用于降级窗口）
        boolean useSnowflake = GrayDecider.chooseSnowflake(c.grayEnabled, c.snowflakePercent, grayKey);
        return useSnowflake ? snowflake.nextId(grayKey) : segment.nextId(grayKey);
    }

    @Override
    public String strategyName() {
        StrategyConfig c = configRef.get();
        if (c.strategy == IdGenProperties.Strategy.SEGMENT) return "SEGMENT";
        if (c.grayEnabled) return "SNOWFLAKE(gray " + c.snowflakePercent + "%)";
        return "SNOWFLAKE";
    }

    public StrategyConfig getConfig() {
        return configRef.get();
    }

    public void update(StrategyConfig newConfig) {
        configRef.set(newConfig);
    }
}
