package com.lx.governance.aichat.repository;

import com.lx.governance.aichat.document.ChatHistoryDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 对话历史搜索 Repository
 */
@Repository
public interface ChatHistorySearchRepository extends ElasticsearchRepository<ChatHistoryDocument, String> {

    /**
     * 根据客户ID和关键词搜索（用户消息或AI回复中包含关键词）
     */
    Page<ChatHistoryDocument> findByCustomerIdAndUserMessageContainingOrCustomerIdAndAiResponseContaining(
            String customerId, String keyword1, String responseCustomerId, String keyword2, Pageable pageable);

    /**
     * 根据客户ID查询最近的对话
     */
    List<ChatHistoryDocument> findTop10ByCustomerIdOrderByCreateTimeDesc(String customerId);
}
