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
}
