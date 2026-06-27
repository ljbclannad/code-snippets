package io.github.messageguard.ops.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.github.messageguard")
public class MessageGuardOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MessageGuardOpsApplication.class, args);
    }
}
