package com.lx.governance.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(
    scanBasePackages = {
        "com.lx.governance.gateway",
        "com.lx.governance.common.auth",
        "com.lx.governance.common.trace",
        "com.lx.governance.common.web"
    },
    exclude = {DataSourceAutoConfiguration.class}
)
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
