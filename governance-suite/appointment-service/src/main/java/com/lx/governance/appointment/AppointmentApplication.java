package com.lx.governance.appointment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 预约服务启动类
 */
@SpringBootApplication(scanBasePackages = {
    "com.lx.governance.appointment",
    "com.lx.governance.common.auth",
    "com.lx.governance.common.trace",
    "com.lx.governance.common.web",
    "com.lx.governance.common.api"
})
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
public class AppointmentApplication {
    public static void main(String[] args) {
        SpringApplication.run(AppointmentApplication.class, args);
    }
}
