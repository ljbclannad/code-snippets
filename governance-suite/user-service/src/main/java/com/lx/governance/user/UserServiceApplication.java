package com.lx.governance.user;

import com.lx.governance.common.filter.TraceIdFilter;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = {
    "com.lx.governance.user",
    "com.lx.governance.common.auth",
    "com.lx.governance.common.trace",
    "com.lx.governance.common.web",
    "com.lx.governance.common.api"
})
@EnableFeignClients
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
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
