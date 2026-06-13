package com.lx.governance.knowledgebase.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 配置
 */
@Slf4j
@Configuration
public class MilvusConfig {

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String milvusHost;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int milvusPort;

    @Value("${spring.ai.vectorstore.milvus.database-name:default}")
    private String databaseName;

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        log.info("初始化 MilvusServiceClient: host={}, port={}, database={}",
                milvusHost, milvusPort, databaseName);

        return new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(milvusHost)
                        .withPort(milvusPort)
                        .withDatabaseName(databaseName)
                        .build()
        );
    }
}
