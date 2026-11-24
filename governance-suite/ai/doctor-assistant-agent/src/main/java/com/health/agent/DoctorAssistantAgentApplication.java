package com.health.agent;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 医生助手 ReAct Agent 应用启动类
 */
@SpringBootApplication
@MapperScan(basePackages = "com.health.agent", annotationClass = Mapper.class)
public class DoctorAssistantAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DoctorAssistantAgentApplication.class, args);
        System.out.println("\n========================================");
        System.out.println("医生助手 ReAct Agent 启动成功！");
        System.out.println("访问地址: http://localhost:8088");
        System.out.println("健康检查: http://localhost:8088/actuator/health");
        System.out.println("监控指标: http://localhost:8088/actuator/prometheus");
        System.out.println("========================================\n");
    }
}
