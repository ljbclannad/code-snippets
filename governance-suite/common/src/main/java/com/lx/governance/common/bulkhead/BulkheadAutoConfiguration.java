package com.lx.governance.common.bulkhead;

import feign.Client;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
                                      BulkheadThreadPoolManager poolManager,
                                      BulkheadProperties properties) {
        return new BulkheadFeignClient(defaultClient, poolManager, properties);
    }
}
