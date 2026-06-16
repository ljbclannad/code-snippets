package com.lx.governance.vectormemory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 记忆检索配置属性
 *
 * @author LeJinBo
 * @since 2026-05-25
 */
@Data
@Component
@ConfigurationProperties(prefix = "memory.retrieval")
public class MemoryRetrievalProperties {

    /**
     * 混合检索配置
     */
    private HybridConfig hybrid = new HybridConfig();

    /**
     * Rerank 配置
     */
    private RerankConfig rerank = new RerankConfig();

    /**
     * 去重配置
     */
    private DeduplicationConfig deduplication = new DeduplicationConfig();

    @Data
    public static class RerankConfig {
        /**
         * 是否启用 Rerank
         */
        private boolean enabled = true;

        /**
         * Rerank 提供方
         * - embedding: 使用当前 embedding 二次打分
         * - bge-http: 调用外部 BGE Rerank 服务
         */
        private String provider = "embedding";

        /**
         * Rerank 策略
         * - full: 全量重排序（对所有候选记忆重新计算相似度）
         * - partial: 部分重排序（仅对 Redis 缓存的记忆重新计算相似度，Milvus 的保留原始分数）
         */
        private String strategy = "partial";

        /**
         * 外部 Rerank 服务失败时是否降级到 embedding 二次打分
         */
        private boolean fallbackToEmbedding = true;

        /**
         * 外部 Rerank 服务配置
         */
        private RemoteConfig remote = new RemoteConfig();
    }

    @Data
    public static class RemoteConfig {
        /**
         * 外部 Rerank 服务地址
         */
        private String baseUrl = "http://localhost:9997";

        /**
         * 外部 Rerank 服务路径
         */
        private String path = "/v1/rerank";

        /**
         * 外部 Rerank 服务模型标识
         */
        private String model = "bge-reranker-v2-m3";
    }

    @Data
    public static class DeduplicationConfig {
        /**
         * 是否启用语义去重
         */
        private boolean semanticEnabled = true;

        /**
         * 语义相似度阈值（>= 此值视为重复）
         */
        private double semanticThreshold = 0.95;
    }

    @Data
    public static class HybridConfig {
        /**
         * 是否启用关键词检索
         */
        private boolean keywordEnabled = true;

        /**
         * 初始召回数量放大倍数
         */
        private int fetchMultiplier = 2;

        /**
         * 三路召回的初始分数权重
         */
        private WeightConfig weights = new WeightConfig();
    }

    @Data
    public static class WeightConfig {
        /**
         * Redis 最近记忆的基础分数
         */
        private double redisBase = 0.78;

        /**
         * Redis 置信度带来的额外加分上限
         */
        private double redisConfidenceBonus = 0.12;

        /**
         * 关键词召回的基础分数
         */
        private double keywordBase = 0.74;

        /**
         * 标题命中额外加分
         */
        private double keywordTitleBonus = 0.08;

        /**
         * memory_key 命中额外加分
         */
        private double keywordKeyBonus = 0.06;

        /**
         * 内容命中额外加分
         */
        private double keywordContentBonus = 0.05;

        /**
         * 关键词召回的置信度加分上限
         */
        private double keywordConfidenceBonus = 0.08;

        /**
         * Milvus 向量分数缩放系数
         */
        private double vectorScale = 1.0;
    }
}
