package com.lx.governance.common.bulkhead;

import feign.Client;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(Client.class)
@EnableConfigurationProperties(BulkheadProperties.class)
public class BulkheadAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "bulkhead", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Client bulkheadFeignClient(Client defaultClient,
                                      BulkheadThreadPoolManager poolManager,
                                      BulkheadProperties properties) {
        return new BulkheadFeignClient(defaultClient, poolManager, properties);
    }
}
