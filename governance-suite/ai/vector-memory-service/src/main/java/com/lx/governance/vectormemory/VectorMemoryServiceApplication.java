package com.lx.governance.vectormemory;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Vector Memory Service Application
 *
 * 向量记忆服务 - 负责向量化存储和检索用户记忆
 */
@SpringBootApplication(exclude = {
        org.springframework.ai.autoconfigure.vectorstore.milvus.MilvusVectorStoreAutoConfiguration.class
})
@EnableAsync
@EnableRetry
@EnableScheduling
@MapperScan({
        "com.lx.governance.vectormemory.mapper",
        "com.lx.governance.vectormemory.repo"
})
public class VectorMemoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VectorMemoryServiceApplication.class, args);
    }
}
