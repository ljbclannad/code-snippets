package com.lx.governance.vectormemory.config;

import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.MilvusVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import lombok.extern.slf4j.Slf4j;

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

    /**
     * Milvus 服务地址。
     *
     * 表示当前应用要连接的 Milvus 节点主机名或 IP。
     * 在本地开发时通常是 `localhost`，在 Docker Compose 或集群环境中，
     * 往往会配置成容器服务名或内网地址。
     *
     * 它和 `milvusPort` 一起决定客户端最终连到哪个 Milvus 实例。
     */
    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String milvusHost;

    /**
     * Milvus 服务端口。
     *
     * 这是 Milvus gRPC 服务的访问端口，默认是 `19530`。
     * Spring AI 的 Milvus VectorStore 和 Milvus Java Client 都通过这个端口建立连接，
     * 因此如果 Milvus 部署时改过端口，这里必须同步修改。
     */
    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int milvusPort;

    /**
     * Milvus 数据库名。
     *
     * 这个字段不是“业务数据库”的概念，而是 Milvus 2.x 自己提供的逻辑库隔离能力。
     * 它决定：
     * 1. 当前 VectorStore 创建和查询时落在哪个 Milvus database 下
     * 2. 同名 collection 是否和其他环境/业务隔离
     * 3. 客户端连接建立后默认操作的是哪一个逻辑库
     *
     * 默认值是 `default`。如果后续需要区分不同环境、租户或业务线，
     * 可以通过这个字段把向量集合隔离到不同的 Milvus database 中，
     * 避免所有 collection 都堆在同一个逻辑空间下。
     */
    @Value("${spring.ai.vectorstore.milvus.database-name:default}")
    private String databaseName;

    /**
     * 向量维度。
     *
     * 该字段必须与 EmbeddingModel 输出的维度严格一致。
     * 例如如果 embedding 模型输出 1024 维向量，那么这里也必须是 1024，
     * 否则在创建 collection 或插入向量时会发生维度不匹配错误。
     *
     * 这个字段直接影响：
     * 1. Milvus collection 的 schema 定义
     * 2. 向量写入校验
     * 3. 后续检索时的数据兼容性
     *
     * 一旦线上 collection 已存在，修改该值通常意味着需要重建 collection。
     */
    @Value("${spring.ai.vectorstore.milvus.embedding-dimension:1024}")
    private int embeddingDimension;

    @Value("${milvus.reset-collections-on-startup:false}")
    private boolean resetCollectionsOnStartup;

    /**
     * 创建 Milvus 客户端
     */
    @Bean
    public MilvusServiceClient milvusServiceClient() {
        log.info("Initializing MilvusServiceClient: host={}, port={}, database={}",
                milvusHost, milvusPort, databaseName);

        MilvusServiceClient client = new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(milvusHost)
                        .withPort(milvusPort)
                        .withDatabaseName(databaseName)
                        .build());

        if (!resetCollectionsOnStartup) {
            log.info("保留现有 Milvus collections；如需重建请设置 milvus.reset-collections-on-startup=true");
            return client;
        }

        // 仅在显式开启时删除旧集合，避免每次启动都触发重新建索引和噪音日志。
        try {
            if (client.hasCollection(io.milvus.param.collection.HasCollectionParam.newBuilder()
                    .withCollectionName("ai_group_memories").build()).getData()) {
                log.warn("删除旧集合: ai_group_memories");
                client.dropCollection(io.milvus.param.collection.DropCollectionParam.newBuilder()
                        .withCollectionName("ai_group_memories").build());
            }
            if (client.hasCollection(io.milvus.param.collection.HasCollectionParam.newBuilder()
                    .withCollectionName("ai_user_memories").build()).getData()) {
                log.warn("删除旧集合: ai_user_memories");
                client.dropCollection(io.milvus.param.collection.DropCollectionParam.newBuilder()
                        .withCollectionName("ai_user_memories").build());
            }
        } catch (Exception e) {
            log.error("删除旧集合失败", e);
        }

        return client;
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

        // IndexType.IVF_FLAT:
        // 使用 IVF（倒排文件）做分桶，再在桶内做 FLAT 精确比对。
        // 这是一个在检索速度和实现复杂度之间比较平衡的索引类型，
        // 适合当前文本向量检索场景，通常比纯 FLAT 全量暴力搜索更快。
        //
        // MetricType.COSINE:
        // 使用余弦相似度作为向量相似性度量方式。
        // 对文本 embedding 来说，向量方向通常比向量绝对长度更重要，
        // 因此 COSINE 是最常见也最合理的配置之一。
        MilvusVectorStore.MilvusVectorStoreConfig config = MilvusVectorStore.MilvusVectorStoreConfig.builder()
                .withCollectionName("ai_group_memories")
                .withDatabaseName(databaseName)
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withEmbeddingDimension(embeddingDimension)
                .build();

        ensureCollectionAndTriggerAsyncLoad(milvusServiceClient, "ai_group_memories");

        BatchingStrategy batchingStrategy = new TokenCountBatchingStrategy();
        return new MilvusVectorStore(milvusServiceClient, embeddingModel, config, false, batchingStrategy);
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

        // 这里沿用与群组记忆相同的索引和度量配置：
        // - IVF_FLAT：在当前数据规模下提供较稳定的查询性能
        // - COSINE：与文本 embedding 的语义相似度计算方式保持一致
        MilvusVectorStore.MilvusVectorStoreConfig config = MilvusVectorStore.MilvusVectorStoreConfig.builder()
                .withCollectionName("ai_user_memories")
                .withDatabaseName(databaseName)
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withEmbeddingDimension(embeddingDimension)
                .build();

        ensureCollectionAndTriggerAsyncLoad(milvusServiceClient, "ai_user_memories");

        BatchingStrategy batchingStrategy = new TokenCountBatchingStrategy();
        return new MilvusVectorStore(milvusServiceClient, embeddingModel, config, false, batchingStrategy);
    }

    private void ensureCollectionAndTriggerAsyncLoad(
            MilvusServiceClient milvusServiceClient,
            String collectionName) {

        if (!hasCollection(milvusServiceClient, collectionName)) {
            log.info("Milvus collection 不存在，开始创建: {}", collectionName);
            createCollection(milvusServiceClient, collectionName);
        }

        if (!hasIndex(milvusServiceClient, collectionName)) {
            log.info("Milvus index 不存在，开始创建: {}", collectionName);
            createIndex(milvusServiceClient, collectionName);
        }

        triggerAsyncLoad(milvusServiceClient, collectionName);
    }

    private boolean hasCollection(MilvusServiceClient milvusServiceClient, String collectionName) {
        return milvusServiceClient.hasCollection(HasCollectionParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(collectionName)
                .build()).getData();
    }

    private boolean hasIndex(MilvusServiceClient milvusServiceClient, String collectionName) {
        try {
            var response = milvusServiceClient.describeIndex(DescribeIndexParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(collectionName)
                    .build());
            DescribeIndexResponse data = response.getData();
            return data != null && data.getIndexDescriptionsCount() > 0;
        } catch (Exception e) {
            log.info("Milvus index 尚不存在: collection={}", collectionName);
            return false;
        }
    }

    private void createCollection(
            MilvusServiceClient milvusServiceClient,
            String collectionName) {

        FieldType docIdFieldType = FieldType.newBuilder()
                .withName(MilvusVectorStore.DOC_ID_FIELD_NAME)
                .withDataType(DataType.VarChar)
                .withMaxLength(36)
                .withPrimaryKey(true)
                .withAutoID(false)
                .build();
        FieldType contentFieldType = FieldType.newBuilder()
                .withName(MilvusVectorStore.CONTENT_FIELD_NAME)
                .withDataType(DataType.VarChar)
                .withMaxLength(65535)
                .build();
        FieldType metadataFieldType = FieldType.newBuilder()
                .withName(MilvusVectorStore.METADATA_FIELD_NAME)
                .withDataType(DataType.JSON)
                .build();
        FieldType embeddingFieldType = FieldType.newBuilder()
                .withName(MilvusVectorStore.EMBEDDING_FIELD_NAME)
                .withDataType(DataType.FloatVector)
                .withDimension(embeddingDimension)
                .build();

        CreateCollectionParam createCollectionReq = CreateCollectionParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(collectionName)
                .withDescription("Spring AI Vector Store")
                .addFieldType(docIdFieldType)
                .addFieldType(contentFieldType)
                .addFieldType(metadataFieldType)
                .addFieldType(embeddingFieldType)
                .build();

        var status = milvusServiceClient.createCollection(createCollectionReq);
        if (status.getException() != null) {
            throw new RuntimeException("Failed to create Milvus collection " + collectionName,
                    status.getException());
        }
    }

    private void createIndex(
            MilvusServiceClient milvusServiceClient,
            String collectionName) {

        var status = milvusServiceClient.createIndex(CreateIndexParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(collectionName)
                .withFieldName(MilvusVectorStore.EMBEDDING_FIELD_NAME)
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"nlist\":1024}")
                .withSyncMode(Boolean.FALSE)
                .build());

        if (status.getException() != null) {
            throw new RuntimeException("Failed to create Milvus index " + collectionName,
                    status.getException());
        }
    }

    private void triggerAsyncLoad(MilvusServiceClient milvusServiceClient, String collectionName) {
        var status = milvusServiceClient.loadCollection(LoadCollectionParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(collectionName)
                .withSyncLoad(Boolean.FALSE)
                .build());

        if (status.getException() != null) {
            throw new RuntimeException("Failed to trigger async load for " + collectionName,
                    status.getException());
        }

        log.info("已触发 Milvus collection 异步加载: {}", collectionName);
    }
}
