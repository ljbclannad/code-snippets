package com.lx.governance.inventory;

import io.micrometer.tracing.Tracer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

import com.lx.governance.common.filter.TraceIdFilter;

@SpringBootApplication
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
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
