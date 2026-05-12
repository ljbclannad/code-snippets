package com.lx.governance.order.config;

import com.alibaba.cloud.sentinel.feign.SentinelFeign;
import feign.Contract;
import feign.Feign;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;

@Configuration
public class FeignConfig {

    @Bean
    public Contract feignContract() {
        return new SpringMvcContract();
    }

    @Bean
    @Primary
    @Scope("prototype")
    public Feign.Builder feignBuilder(Contract feignContract) {
        return SentinelFeign.builder().contract(feignContract);
    }
}
