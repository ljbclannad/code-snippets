package com.lx.governance.vectormemory.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RedisMemoryIndexService {

    private static final String HASH_KEY_PREFIX = "ai:memory:hash:";
    private static final String RECENT_KEY_PREFIX = "ai:memory:recent:";
    private static final long HASH_TTL_DAYS = 7;
    private static final long RECENT_TTL_HOURS = 24;
    private static final int MAX_RECENT_MEMORIES = 20;
    private static final String MEMORY_FORMAT_SEPARATOR = ":";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 检查记忆是否重复
     */
    public boolean isDuplicate(String groupId, String content) {
        String hashKey = HASH_KEY_PREFIX + groupId;
        String contentHash = md5(content);

        try {
            Boolean exists = redisTemplate.opsForSet().isMember(hashKey, contentHash);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("检查记忆重复失败: groupId={}", groupId, e);
            return false;
        }
    }

    /**
     * 添加记忆到去重索引
     */
    public void addToHashIndex(String groupId, String content) {
        String hashKey = HASH_KEY_PREFIX + groupId;
        String contentHash = md5(content);

        try {
            redisTemplate.opsForSet().add(hashKey, contentHash);
            // 仅在新键时设置TTL，避免重置已存在键的过期时间
            Boolean hasKey = redisTemplate.hasKey(hashKey);
            if (Boolean.FALSE.equals(hasKey)) {
                redisTemplate.expire(hashKey, HASH_TTL_DAYS, TimeUnit.DAYS);
            }
            log.debug("添加记忆到去重索引: groupId={}, hash={}", groupId, contentHash);
        } catch (Exception e) {
            log.error("添加去重索引失败: groupId={}", groupId, e);
        }
    }

    /**
     * 缓存最近记忆
     */
    public void cacheRecentMemory(String groupId, Long memoryId, String content, double score) {
        String recentKey = RECENT_KEY_PREFIX + groupId;

        try {
            // 使用Sorted Set，score为时间戳
            // 格式: "memoryId:content"，使用常量分隔符
            String value = memoryId + MEMORY_FORMAT_SEPARATOR + content;
            redisTemplate.opsForZSet().add(recentKey, value, score);

            // 保持最多20条
            Long size = redisTemplate.opsForZSet().size(recentKey);
            if (size != null && size > MAX_RECENT_MEMORIES) {
                redisTemplate.opsForZSet().removeRange(recentKey, 0, size - MAX_RECENT_MEMORIES - 1);
            }

            // 仅在新键时设置TTL，避免重置已存在键的过期时间
            Boolean hasKey = redisTemplate.hasKey(recentKey);
            if (Boolean.FALSE.equals(hasKey)) {
                redisTemplate.expire(recentKey, RECENT_TTL_HOURS, TimeUnit.HOURS);
            }

            log.debug("缓存最近记忆: groupId={}, memoryId={}", groupId, memoryId);
        } catch (Exception e) {
            log.error("缓存最近记忆失败: groupId={}", groupId, e);
        }
    }

    /**
     * 获取最近记忆
     */
    public List<String> getRecentMemories(String groupId, int limit) {
        String recentKey = RECENT_KEY_PREFIX + groupId;

        try {
            // 按score倒序获取（最新的在前）
            Set<Object> results = redisTemplate.opsForZSet()
                .reverseRange(recentKey, 0, limit - 1);

            if (results == null || results.isEmpty()) {
                return List.of();
            }

            return results.stream()
                .map(Object::toString)
                .map(s -> {
                    // 安全地提取content部分，防止数组越界
                    String[] parts = s.split(MEMORY_FORMAT_SEPARATOR, 2);
                    return parts.length == 2 ? parts[1] : s;
                })
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("获取最近记忆失败: groupId={}", groupId, e);
            return List.of();
        }
    }

    /**
     * 计算MD5哈希
     */
    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5计算失败", e);
        }
    }
}
