package com.lx.governance.order;

import io.micrometer.tracing.Tracer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.lx.governance.common.filter.TraceIdFilter;

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
    public FilterRegistrationBean<TraceIdFilter> traceFilter(Tracer tracer) {
        FilterRegistrationBean<TraceIdFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new TraceIdFilter(tracer));
        bean.addUrlPatterns("/*");
        bean.setOrder(-100);
        return bean;
    }
}
