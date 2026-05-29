package com.lx.governance.common.bulkhead;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import feign.Client;

@Configuration
@ConditionalOnClass(Client.class)
@EnableConfigurationProperties(BulkheadProperties.class)
@ComponentScan(basePackageClasses = BulkheadThreadPoolManager.class)
public class BulkheadAutoConfiguration {

    @Bean(name = "defaultFeignClient")
    @ConditionalOnMissingBean(name = "defaultFeignClient")
    public Client defaultFeignClient() {
        return new Client.Default(null, null);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "bulkhead", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Client bulkheadFeignClient(@Qualifier("defaultFeignClient") Client defaultClient,
            LoadBalancerClient loadBalancerClient,
            LoadBalancerClientFactory loadBalancerClientFactory,
            ObjectProvider<List<LoadBalancerFeignRequestTransformer>> transformersProvider,
            BulkheadThreadPoolManager poolManager,
            BulkheadProperties properties) {
        Client loadBalancedClient = new FeignBlockingLoadBalancerClient(
                defaultClient,
                loadBalancerClient,
                loadBalancerClientFactory,
                transformersProvider.getIfAvailable(List::of));
        return new BulkheadFeignClient(loadBalancedClient, poolManager, properties);
    }
}
