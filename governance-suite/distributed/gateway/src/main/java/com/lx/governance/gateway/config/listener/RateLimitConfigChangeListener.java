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
     * 触发链路（当前项目使用 Spring Cloud Alibaba 2022.0.0.0）：
     * 1. Nacos 中已加载且允许刷新的配置发生变更，Nacos Listener 收到新内容。
     * 2. NacosContextRefresher 中的 Listener 发布 RefreshEvent。
     * 3. RefreshEventListener 在应用就绪后调用 ContextRefresher.refresh()。
     * 4. refresh() 先执行 refreshEnvironment()：重新加载配置源、更新 Environment，
     *    比较更新前后的属性并发布携带变更 key 的 EnvironmentChangeEvent。
     * 5. refresh() 再调用 RefreshScope.refreshAll()：销毁 RefreshScope 缓存的目标 Bean，
     *    然后发布 RefreshScopeRefreshedEvent；此时目标 Bean 尚未统一重新创建。
     * 6. 当前方法收到 RefreshScopeRefreshedEvent 后，通过 RateLimitProperties 的
     *    作用域代理读取配置；首次访问会重新创建目标 Bean 并绑定最新属性，再通知监控。
     *
     * 说明：
     * - RateLimitProperties 标注了 @RefreshScope，因此通过代理再次访问时能读取新配置。
     * - refreshAll() 清除整个 RefreshScope 缓存，并非只处理变更 key 对应的 Bean。
     * - 当前方法由配置刷新事件触发，不会在每次请求时执行。
     * - 较新版本的 Spring Cloud Alibaba 会先发布 NacosConfigRefreshEvent，再由
     *   NacosConfigRefreshEventListener 将其转换为 RefreshEvent；当前版本直接发布后者。
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
