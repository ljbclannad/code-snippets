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

    @Data
    public static class RerankConfig {
        /**
         * 是否启用 Rerank
         */
        private boolean enabled = true;

        /**
         * Rerank 策略
         * - full: 全量重排序（对所有候选记忆重新计算相似度）
         * - partial: 部分重排序（仅对 Redis 缓存的记忆重新计算相似度，Milvus 的保留原始分数）
         */
        private String strategy = "partial";
    }
}
