package io.github.messageguard.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.github.messageguard")
public class MessageGuardDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MessageGuardDemoApplication.class, args);
    }
}
