package com.lx.governance.aichat;

import com.lx.governance.agentplatform.demo.FollowUpDemoIdentityConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@Import(FollowUpDemoIdentityConfiguration.class)
@EnableAsync
@EnableScheduling
@MapperScan("com.lx.governance.aichat.mapper")
public class AiChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiChatServiceApplication.class, args);
    }
}
