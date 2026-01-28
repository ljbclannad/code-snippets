package com.example.idgendemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class IdgenDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdgenDemoApplication.class, args);
    }
}
