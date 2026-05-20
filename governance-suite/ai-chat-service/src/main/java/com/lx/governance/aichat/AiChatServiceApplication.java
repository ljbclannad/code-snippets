package com.lx.governance.aichat;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@MapperScan("com.lx.governance.aichat.mapper")
public class AiChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiChatServiceApplication.class, args);
    }
}
