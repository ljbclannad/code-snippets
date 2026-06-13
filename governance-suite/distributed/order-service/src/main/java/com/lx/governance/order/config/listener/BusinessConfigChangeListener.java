package com.lx.governance.order.config.listener;

import com.lx.governance.order.config.properties.BusinessProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Slf4j
public class BusinessConfigChangeListener {

    private final BusinessProperties businessProperties;
    private final StringRedisTemplate redisTemplate;

    public BusinessConfigChangeListener(
            BusinessProperties businessProperties,
            StringRedisTemplate redisTemplate) {
        this.businessProperties = businessProperties;
        this.redisTemplate = redisTemplate;
    }

    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        boolean seckillEnabled = businessProperties.isSeckillEnabled();
        log.warn("秒杀开关状态变更: enabled={}", seckillEnabled);

        if (!seckillEnabled) {
            clearSeckillCache();
        }
    }

    private void clearSeckillCache() {
        Set<String> keys = redisTemplate.keys("seckill:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("已清理秒杀缓存: count={}", keys.size());
        }
    }
}
