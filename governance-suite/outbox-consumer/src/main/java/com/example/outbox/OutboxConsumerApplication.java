package com.example.outbox;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.example.outbox.mapper")
public class OutboxConsumerApplication {
    public static void main(String[] args) {
        SpringApplication.run(OutboxConsumerApplication.class, args);
    }
}
