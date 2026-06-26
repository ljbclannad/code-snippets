package com.lx.governance.nacossimulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class NacosSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(NacosSimulatorApplication.class, args);
    }
}
