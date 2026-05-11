package com.lx.governance.order;

import com.lx.governance.common.web.TraceIdFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
    "com.lx.governance.order",
    "com.lx.governance.common.auth",
    "com.lx.governance.common.trace",
    "com.lx.governance.common.web",
    "com.lx.governance.common.api"
})
@EnableFeignClients
@EnableScheduling
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceFilter() {
        FilterRegistrationBean<TraceIdFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new TraceIdFilter());
        bean.addUrlPatterns("/*");
        bean.setOrder(-100);
        return bean;
    }
}
