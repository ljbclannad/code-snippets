package com.health.agent.vector;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.collection.*;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;

/**
 * Milvus 向量数据库客户端
 */
@Slf4j
@Component
public class MilvusClient {

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Value("${milvus.collection-name:medical_cases}")
    private String collectionName;

    @Value("${milvus.dimension:768}")
    private int dimension;

    @Value("${milvus.enabled:false}")
    private boolean enabled;

    private MilvusServiceClient client;

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("Milvus 未启用，跳过初始化");
            return;
        }

        try {
            // 连接 Milvus
            client = new MilvusServiceClient(
                ConnectParam.newBuilder()
                    .withHost(host)
                    .withPort(port)
                    .build()
            );

            log.info("Milvus 连接成功: {}:{}", host, port);

            // 检查集合是否存在
            if (!hasCollection()) {
                createCollection();
                createIndex();
                log.info("Milvus 集合创建成功: {}", collectionName);
            } else {
                log.info("Milvus 集合已存在: {}", collectionName);
            }

            // 加载集合到内存
            loadCollection();

        } catch (Exception e) {
            log.error("Milvus 初始化失败", e);
            enabled = false;
        }
    }

    @PreDestroy
    public void destroy() {
        if (client != null) {
            client.close();
            log.info("Milvus 连接已关闭");
        }
    }

    /**
     * 检查集合是否存在
     */
    private boolean hasCollection() {
        return client.hasCollection(
            HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build()
        ).getData();
    }

    /**
     * 创建集合
     */
    private void createCollection() {
        // 定义字段
        List<FieldType> fields = Arrays.asList(
            FieldType.newBuilder()
                .withName("case_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .withPrimaryKey(true)
                .withAutoID(false)
                .build(),
            FieldType.newBuilder()
                .withName("customer_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .build(),
            FieldType.newBuilder()
                .withName("description")
                .withDataType(DataType.VarChar)
                .withMaxLength(2000)
                .build(),
            FieldType.newBuilder()
                .withName("diagnosis")
                .withDataType(DataType.VarChar)
                .withMaxLength(500)
                .build(),
            FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(dimension)
                .build()
        );

        // 创建集合
        client.createCollection(
            CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldTypes(fields)
                .build()
        );
    }

    /**
     * 创建索引
     */
    private void createIndex() {
        client.createIndex(
            CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("embedding")
                .withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.L2)
                .withExtraParam("{\"nlist\":128}")
                .build()
        );
    }

    /**
     * 加载集合到内存
     */
    private void loadCollection() {
        client.loadCollection(
            LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build()
        );
    }

    /**
     * 插入向量
     */
    public void insert(CaseVector caseVector) {
        if (!enabled) {
            log.warn("Milvus 未启用，跳过插入操作");
            return;
        }

        try {
            List<InsertParam.Field> fields = Arrays.asList(
                new InsertParam.Field("case_id", Collections.singletonList(caseVector.getCaseId())),
                new InsertParam.Field("customer_id", Collections.singletonList(caseVector.getCustomerId())),
                new InsertParam.Field("description", Collections.singletonList(caseVector.getDescription())),
                new InsertParam.Field("diagnosis", Collections.singletonList(caseVector.getDiagnosis())),
                new InsertParam.Field("embedding", Collections.singletonList(caseVector.getEmbedding()))
            );

            client.insert(
                InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(fields)
                    .build()
            );

            log.info("向量插入成功: caseId={}", caseVector.getCaseId());

        } catch (Exception e) {
            log.error("向量插入失败", e);
        }
    }

    /**
     * 批量插入向量
     */
    public void batchInsert(List<CaseVector> caseVectors) {
        if (!enabled || caseVectors.isEmpty()) {
            return;
        }

        try {
            List<String> caseIds = new ArrayList<>();
            List<String> customerIds = new ArrayList<>();
            List<String> descriptions = new ArrayList<>();
            List<String> diagnoses = new ArrayList<>();
            List<List<Float>> embeddings = new ArrayList<>();

            for (CaseVector cv : caseVectors) {
                caseIds.add(cv.getCaseId());
                customerIds.add(cv.getCustomerId());
                descriptions.add(cv.getDescription());
                diagnoses.add(cv.getDiagnosis());
                embeddings.add(cv.getEmbedding());
            }

            List<InsertParam.Field> fields = Arrays.asList(
                new InsertParam.Field("case_id", caseIds),
                new InsertParam.Field("customer_id", customerIds),
                new InsertParam.Field("description", descriptions),
                new InsertParam.Field("diagnosis", diagnoses),
                new InsertParam.Field("embedding", embeddings)
            );

            client.insert(
                InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(fields)
                    .build()
            );

            log.info("批量插入向量成功: count={}", caseVectors.size());

        } catch (Exception e) {
            log.error("批量插入向量失败", e);
        }
    }

    /**
     * 搜索相似向量
     */
    public List<CaseVector> search(List<Float> queryVector, int topK) {
        if (!enabled) {
            log.warn("Milvus 未启用，返回空结果");
            return Collections.emptyList();
        }

        try {
            SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withMetricType(MetricType.L2)
                .withOutFields(Arrays.asList("case_id", "customer_id", "description", "diagnosis"))
                .withTopK(topK)
                .withVectors(Collections.singletonList(queryVector))
                .withVectorFieldName("embedding")
                .withParams("{\"nprobe\":10}")
                .build();

            io.milvus.response.SearchResultsWrapper wrapper =
                new SearchResultsWrapper(client.search(searchParam).getData().getResults());

            List<CaseVector> results = new ArrayList<>();
            for (int i = 0; i < wrapper.getIDScore(0).size(); i++) {
                CaseVector cv = new CaseVector();
                cv.setCaseId((String) wrapper.getIDScore(0).get(i).get("case_id"));
                cv.setCustomerId((String) wrapper.getIDScore(0).get(i).get("customer_id"));
                cv.setDescription((String) wrapper.getIDScore(0).get(i).get("description"));
                cv.setDiagnosis((String) wrapper.getIDScore(0).get(i).get("diagnosis"));
                cv.setSimilarity(wrapper.getIDScore(0).get(i).getScore());
                results.add(cv);
            }

            log.info("向量搜索成功: topK={}, results={}", topK, results.size());
            return results;

        } catch (Exception e) {
            log.error("向量搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 检查 Milvus 是否可用
     */
    public boolean isEnabled() {
        return enabled && client != null;
    }
}
