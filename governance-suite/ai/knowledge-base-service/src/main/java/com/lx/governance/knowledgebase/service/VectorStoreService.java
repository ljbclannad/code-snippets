package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.domain.DocumentChunk;
import com.lx.governance.knowledgebase.dto.SearchRequest;
import com.lx.governance.knowledgebase.dto.SearchResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.HasCollectionParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.MilvusVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 向量存储服务
 * 使用 Spring AI MilvusVectorStore 进行向量存储和检索
 */
@Slf4j
@Service
public class VectorStoreService {

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String milvusHost;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int milvusPort;

    @Value("${spring.ai.vectorstore.milvus.database-name:default}")
    private String databaseName;

    @Value("${knowledge-base.embedding.dimension:1024}")
    private int embeddingDimension;

    private final MilvusServiceClient milvusClient;
    private final org.springframework.ai.embedding.EmbeddingModel embeddingModel;

    // 缓存已创建的 VectorStore 实例
    private final Map<String, VectorStore> vectorStoreCache = new ConcurrentHashMap<>();

    public VectorStoreService(
            MilvusServiceClient milvusClient,
            org.springframework.ai.embedding.EmbeddingModel embeddingModel) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
        log.info("VectorStoreService 初始化完成");
    }

    /**
     * 获取或创建 VectorStore
     */
    private VectorStore getOrCreateVectorStore(String collectionName) {
        return vectorStoreCache.computeIfAbsent(collectionName, name -> {
            log.info("创建 VectorStore: {}", name);
            VectorStore vectorStore = createVectorStore(name);
            initializeVectorStore(vectorStore, name);
            return vectorStore;
        });
    }

    protected VectorStore createVectorStore(String collectionName) {
        MilvusVectorStore.MilvusVectorStoreConfig config = MilvusVectorStore.MilvusVectorStoreConfig.builder()
                .withCollectionName(collectionName)
                .withDatabaseName(databaseName)
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.COSINE)
                .withEmbeddingDimension(embeddingDimension)
                .build();

        return new MilvusVectorStore(milvusClient, embeddingModel, config, true,
                new org.springframework.ai.embedding.TokenCountBatchingStrategy());
    }

    private void initializeVectorStore(VectorStore vectorStore, String collectionName) {
        if (vectorStore instanceof InitializingBean initializingBean) {
            try {
                initializingBean.afterPropertiesSet();
                log.info("集合已初始化: {}", collectionName);
            } catch (Exception e) {
                log.error("初始化 VectorStore 失败: {}", collectionName, e);
                throw new RuntimeException("初始化 VectorStore 失败", e);
            }
        }
    }

    /**
     * 创建集合
     */
    public void createCollection(String collectionName) {
        // Spring AI MilvusVectorStore 会自动创建集合
        getOrCreateVectorStore(collectionName);
        log.info("集合已就绪: {}", collectionName);
    }

    /**
     * 检查集合是否存在
     */
    public boolean hasCollection(String collectionName) {
        try {
            return milvusClient.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build()
            ).getData();
        } catch (Exception e) {
            log.error("检查集合失败: {}", collectionName, e);
            return false;
        }
    }

    /**
     * 批量插入文档切片
     */
    public void insertChunks(String collectionName, List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }

        log.info("开始插入向量，集合: {}, 切片数: {}", collectionName, chunks.size());

        // 获取或创建 VectorStore（会自动创建集合）
        VectorStore vectorStore = getOrCreateVectorStore(collectionName);

        // 转换为 Spring AI Document
        List<Document> documents = chunks.stream()
                .map(chunk -> {
                    Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
                    metadata.put("chunk_id", chunk.getChunkId());
                    metadata.put("doc_id", chunk.getDocId());
                    metadata.put("chunk_index", chunk.getChunkIndex());

                    return new Document(
                            chunk.getChunkId(),
                            chunk.getContent(),
                            metadata
                    );
                })
                .collect(Collectors.toList());

        // 批量插入
        vectorStore.add(documents);

        log.info("向量插入完成: {} 个切片", chunks.size());
    }

    /**
     * 语义检索
     */
    public List<SearchResult> search(
            String collectionName,
            SearchRequest request) {

        log.info("开始检索: collection={}, query={}, topK={}", collectionName, request.getQuery(), request.getTopK());

        if (!hasCollection(collectionName)) {
            log.warn("集合不存在: {}", collectionName);
            return Collections.emptyList();
        }

        VectorStore vectorStore = getOrCreateVectorStore(collectionName);

        // 构建检索请求
        org.springframework.ai.vectorstore.SearchRequest searchRequest =
                org.springframework.ai.vectorstore.SearchRequest.query(request.getQuery())
                        .withTopK(Math.min(request.getTopK() * 5, 100))
                        .withSimilarityThreshold(request.getSimilarityThreshold());

        // 添加文档ID过滤
        if (request.getDocIds() != null && !request.getDocIds().isEmpty()) {
            String filterExpression = "doc_id in [" +
                    request.getDocIds().stream()
                            .map(id -> "\"" + id + "\"")
                            .collect(Collectors.joining(",")) + "]";
            searchRequest = searchRequest.withFilterExpression(filterExpression);
        }

        // 执行检索
        List<Document> results = vectorStore.similaritySearch(searchRequest);

        // 转换结果
        List<SearchResult> searchResults = results.stream()
                .map(doc -> {
                    Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                    String chunkId = (String) metadata.remove("chunk_id");
                    String docId = (String) metadata.remove("doc_id");

                    // 从 metadata 中获取 distance 或 score
                    Double score = 1.0;
                    if (metadata.containsKey("distance")) {
                        // Milvus 返回的是距离，对于 COSINE，距离越小越相似
                        // 我们转换为相似度分数 (1 - distance)
                        Double distance = ((Number) metadata.remove("distance")).doubleValue();
                        score = 1.0 - distance;
                    }

                    return SearchResult.builder()
                            .chunkId(chunkId)
                            .docId(docId)
                            .title(asString(metadata.get("title")))
                            .tags(asString(metadata.get("tags")))
                            .businessDomain(asString(metadata.get("business_domain")))
                            .source(asString(metadata.get("source")))
                            .applicableScope(asString(metadata.get("applicable_scope")))
                            .trustLevel(asString(metadata.get("trust_level")))
                            .content(doc.getContent())
                            .score(score)
                            .hitReason("vector")
                            .metadata(metadata)
                            .build();
                })
                .filter(result -> matchesFilters(result, request))
                .sorted((left, right) -> Double.compare(right.getScore(), left.getScore()))
                .limit(request.getTopK())
                .collect(Collectors.toList());

        log.info("检索完成: 结果数={}", searchResults.size());
        return searchResults;
    }

    public void deleteChunks(String collectionName, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty() || !hasCollection(collectionName)) {
            return;
        }
        VectorStore vectorStore = getOrCreateVectorStore(collectionName);
        vectorStore.delete(chunkIds);
        log.info("删除向量切片完成: collection={}, count={}", collectionName, chunkIds.size());
    }

    private boolean matchesFilters(SearchResult result, SearchRequest request) {
        if (!containsTag(result.getTags(), request.getTags())) {
            return false;
        }
        if (!matchesEqualsFilter(result.getBusinessDomain(), request.getBusinessDomain())) {
            return false;
        }
        if (!matchesEqualsFilter(result.getSource(), request.getSource())) {
            return false;
        }
        if (!matchesEqualsFilter(result.getApplicableScope(), request.getApplicableScope())) {
            return false;
        }
        if (!matchesEqualsFilter(result.getTrustLevel(), request.getTrustLevel())) {
            return false;
        }
        if (request.getTitleKeyword() != null && !request.getTitleKeyword().isBlank()) {
            return result.getTitle() != null && result.getTitle().contains(request.getTitleKeyword());
        }
        return true;
    }

    private boolean containsTag(String tags, List<String> expectedTags) {
        if (expectedTags == null || expectedTags.isEmpty()) {
            return true;
        }
        if (tags == null || tags.isBlank()) {
            return false;
        }
        return expectedTags.stream().allMatch(tags::contains);
    }

    private boolean matchesEqualsFilter(String actual, String expected) {
        return expected == null || expected.isBlank() || Objects.equals(actual, expected);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 删除集合
     */
    public void dropCollection(String collectionName) {
        if (hasCollection(collectionName)) {
            try {
                milvusClient.dropCollection(
                        DropCollectionParam.newBuilder()
                                .withCollectionName(collectionName)
                                .build()
                );
                vectorStoreCache.remove(collectionName);
                log.info("删除集合: {}", collectionName);
            } catch (Exception e) {
                log.error("删除集合失败: {}", collectionName, e);
                throw new RuntimeException("删除集合失败", e);
            }
        }
    }
}
