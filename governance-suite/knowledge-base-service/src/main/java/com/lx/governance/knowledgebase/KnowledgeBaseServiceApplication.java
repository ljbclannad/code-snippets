package com.lx.governance.knowledgebase;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Knowledge Base Service Application
 * 知识库管理服务 - 提供文档解析、语义切片、向量检索能力
 */
@SpringBootApplication
@EnableAsync
@MapperScan("com.lx.governance.knowledgebase.mapper")
public class KnowledgeBaseServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(KnowledgeBaseServiceApplication.class, args);
    }
}
