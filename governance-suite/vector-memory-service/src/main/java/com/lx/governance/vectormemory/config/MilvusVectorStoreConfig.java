package com.lx.governance.vectormemory.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.MilvusVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Milvus VectorStore 配置类
 * 配置两个独立的向量存储：
 * 1. groupMemoryVectorStore - 群组记忆向量存储
 * 2. userMemoryVectorStore - 用户记忆向量存储
 *
 * @author LeJinBo
 * @since 2026-05-13
 */
@Slf4j
@Configuration
public class MilvusVectorStoreConfig {

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String milvusHost;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int milvusPort;

    @Value("${spring.ai.vectorstore.milvus.database-name:default}")
    private String databaseName;

    @Value("${spring.ai.vectorstore.milvus.embedding-dimension:1024}")
    private int embeddingDimension;

    /**
     * 创建 Milvus 客户端
     */
    @Bean
    public MilvusServiceClient milvusServiceClient() {
        log.info("Initializing MilvusServiceClient: host={}, port={}, database={}",
                milvusHost, milvusPort, databaseName);

        return new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(milvusHost)
                        .withPort(milvusPort)
                        .withDatabaseName(databaseName)
                        .build()
        );
    }

    /**
     * 群组记忆向量存储 (Primary)
     * Collection: ai_group_memories
     */
    @Primary
    @Bean(name = "groupMemoryVectorStore")
    public VectorStore groupMemoryVectorStore(
            MilvusServiceClient milvusServiceClient,
            @Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {

        log.info("Creating groupMemoryVectorStore with collection: ai_group_memories");

        MilvusVectorStore.MilvusVectorStoreConfig config = MilvusVectorStore.MilvusVectorStoreConfig.builder()
                .withCollectionName("ai_group_memories")
                .withDatabaseName(databaseName)
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withEmbeddingDimension(embeddingDimension)
                .build();

        return new MilvusVectorStore(milvusServiceClient, embeddingModel, config, true, null);
    }

    /**
     * 用户记忆向量存储
     * Collection: ai_user_memories
     */
    @Bean(name = "userMemoryVectorStore")
    @Qualifier("userMemoryVectorStore")
    public VectorStore userMemoryVectorStore(
            MilvusServiceClient milvusServiceClient,
            @Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {

        log.info("Creating userMemoryVectorStore with collection: ai_user_memories");

        MilvusVectorStore.MilvusVectorStoreConfig config = MilvusVectorStore.MilvusVectorStoreConfig.builder()
                .withCollectionName("ai_user_memories")
                .withDatabaseName(databaseName)
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withEmbeddingDimension(embeddingDimension)
                .build();

        return new MilvusVectorStore(milvusServiceClient, embeddingModel, config, true, null);
    }
}
