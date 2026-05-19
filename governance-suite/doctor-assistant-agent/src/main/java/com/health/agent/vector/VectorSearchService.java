package com.health.agent.vector;

import com.health.agent.metrics.VectorMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 向量检索服务
 */
@Slf4j
@Service
public class VectorSearchService {

    @Autowired
    private MilvusClient milvusClient;

    @Autowired
    private EmbeddingGenerator embeddingGenerator;

    @Autowired
    private VectorMetrics vectorMetrics;

    @Value("${vector.search.top-k:5}")
    private int defaultTopK;

    /**
     * 搜索相似病例
     */
    @Cacheable(value = "vectorSearch", key = "#query + '_' + #topK")
    public List<CaseVector> searchSimilarCases(String query, int topK) {
        long startTime = System.currentTimeMillis();
        log.info("搜索相似病例: query={}, topK={}", query, topK);

        // 记录请求
        vectorMetrics.recordSearchRequest(topK);

        try {
            // 1. 生成查询向量
            List<Float> queryVector = embeddingGenerator.generateEmbedding(query);

            // 2. 向量搜索
            List<CaseVector> results = milvusClient.search(queryVector, topK);

            // 3. 记录成功
            long duration = System.currentTimeMillis() - startTime;
            vectorMetrics.recordSearchSuccess(results.size(), duration);

            log.info("找到相似病例: count={}, duration={}ms", results.size(), duration);
            return results;

        } catch (Exception e) {
            // 记录失败
            vectorMetrics.recordSearchFailure(e.getClass().getSimpleName());
            log.error("向量检索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 搜索相似病例（使用默认 topK）
     */
    public List<CaseVector> searchSimilarCases(String query) {
        return searchSimilarCases(query, defaultTopK);
    }

    /**
     * 添加病例向量
     */
    public void addCase(CaseVector caseVector) {
        long startTime = System.currentTimeMillis();
        log.info("添加病例向量: caseId={}", caseVector.getCaseId());

        // 1. 生成向量
        String text = buildCaseText(caseVector);
        List<Float> embedding = embeddingGenerator.generateEmbedding(text);
        caseVector.setEmbedding(embedding);
        caseVector.setCreatedAt(LocalDateTime.now());

        // 2. 插入 Milvus
        milvusClient.insert(caseVector);

        // 3. 记录指标
        long duration = System.currentTimeMillis() - startTime;
        vectorMetrics.recordInsert(1, duration);
    }

    /**
     * 批量添加病例向量
     */
    public void batchAddCases(List<CaseVector> caseVectors) {
        long startTime = System.currentTimeMillis();
        log.info("批量添加病例向量: count={}", caseVectors.size());

        // 1. 生成向量
        for (CaseVector cv : caseVectors) {
            String text = buildCaseText(cv);
            List<Float> embedding = embeddingGenerator.generateEmbedding(text);
            cv.setEmbedding(embedding);
            cv.setCreatedAt(LocalDateTime.now());
        }

        // 2. 批量插入 Milvus
        milvusClient.batchInsert(caseVectors);

        // 3. 记录指标
        long duration = System.currentTimeMillis() - startTime;
        vectorMetrics.recordInsert(caseVectors.size(), duration);
    }

    /**
     * 构建病例文本（用于生成向量）
     */
    private String buildCaseText(CaseVector caseVector) {
        StringBuilder sb = new StringBuilder();

        // 病例描述
        if (caseVector.getDescription() != null) {
            sb.append("病例描述: ").append(caseVector.getDescription()).append("\n");
        }

        // 诊断结果
        if (caseVector.getDiagnosis() != null) {
            sb.append("诊断结果: ").append(caseVector.getDiagnosis()).append("\n");
        }

        // 症状
        if (caseVector.getSymptoms() != null && !caseVector.getSymptoms().isEmpty()) {
            sb.append("症状: ").append(String.join(", ", caseVector.getSymptoms())).append("\n");
        }

        // 慢性病
        if (caseVector.getChronicDiseases() != null && !caseVector.getChronicDiseases().isEmpty()) {
            sb.append("慢性病: ").append(String.join(", ", caseVector.getChronicDiseases())).append("\n");
        }

        // 处方药物
        if (caseVector.getMedications() != null && !caseVector.getMedications().isEmpty()) {
            sb.append("处方药物: ").append(String.join(", ", caseVector.getMedications())).append("\n");
        }

        return sb.toString();
    }

    /**
     * 检查向量检索是否可用
     */
    public boolean isAvailable() {
        return milvusClient.isEnabled();
    }
}
