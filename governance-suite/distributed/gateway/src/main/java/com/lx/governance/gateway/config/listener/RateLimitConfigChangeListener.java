package com.lx.governance.gateway.config.listener;

import com.lx.governance.gateway.config.properties.RateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RateLimitConfigChangeListener {

    private final RateLimitProperties rateLimitProperties;

    public RateLimitConfigChangeListener(RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }

    /**
     * 触发链路：
     * 1. Nacos 中的可刷新配置发生变更。
     * 2. Nacos Listener 发布 RefreshEvent。
     * 3. RefreshEventListener 收到事件后调用 ContextRefresher.refresh()。
     * 4. ContextRefresher.refresh() 调用 RefreshScope.refreshAll()。
     * 5. RefreshScope.refreshAll() 发布 RefreshScopeRefreshedEvent。
     * 6. 从这一步开始，事件才进入业务层/应用监听器层；当前方法监听到
     *    RefreshScopeRefreshedEvent 后执行，读取最新的限流配置并通知监控。
     *
     * 说明：
     * - RateLimitProperties 标注了 @RefreshScope，因此刷新后会重新绑定最新配置。
     * - 当前方法不是在每次请求时触发，而是在配置刷新完成后触发。
     * - 在 RefreshScopeRefreshedEvent 之前的 Nacos Listener、RefreshEvent、
     *   ContextRefresher.refresh()、RefreshScope.refreshAll() 都属于框架内部刷新链路。
     */
    @EventListener
    public void onRefresh(RefreshScopeRefreshedEvent event) {
        log.info("限流配置已更新: replenishRate={}, burstCapacity={}",
            rateLimitProperties.getReplenishRate(),
            rateLimitProperties.getBurstCapacity());

        notifyMonitoring("rate-limit-config-changed", rateLimitProperties);
    }

    private void notifyMonitoring(String eventName, Object data) {
        log.info("监控通知: event={}, data={}", eventName, data);
    }
}
