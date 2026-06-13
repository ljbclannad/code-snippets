package com.lx.governance.payment;

import io.micrometer.tracing.Tracer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

import com.lx.governance.common.filter.TraceIdFilter;

@SpringBootApplication(scanBasePackages = {
        "com.lx.governance.payment",
        "com.lx.governance.common.auth",
        "com.lx.governance.common.trace",
        "com.lx.governance.common.web",
        "com.lx.governance.common.api"
})
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
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
