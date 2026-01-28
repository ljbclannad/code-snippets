package com.example.idgendemo.id;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

public final class GrayDecider {

    private GrayDecider() {}

    /**
     * 返回 true 表示走 Snowflake；false 表示走 Segment
     * percentSnowflake: 0..100
     */
    public static boolean chooseSnowflake(boolean grayEnabled, int percentSnowflake, String grayKey) {
        if (!grayEnabled) return true; // 默认：按主策略（通常是 Snowflake）
        int p = Math.max(0, Math.min(100, percentSnowflake));

        int bucket;
        if (grayKey == null || grayKey.isBlank()) {
            bucket = ThreadLocalRandom.current().nextInt(100);
        } else {
            bucket = stableBucket(grayKey);
        }
        return bucket < p;
    }

    /**
     * 稳定分桶：对同一个 key，返回 0..99 的稳定值（避免“抖动灰度”）
     */
    private static int stableBucket(String key) {
        // 简化版 hash（够用且可解释）；真实生产可换 Murmur3
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        int h = 0x811C9DC5; // FNV-like
        for (byte b : bytes) {
            h ^= (b & 0xff);
            h *= 16777619;
        }
        h = h & 0x7fffffff;
        return h % 100;
    }
}
