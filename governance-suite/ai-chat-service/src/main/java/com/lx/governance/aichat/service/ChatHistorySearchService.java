package com.lx.governance.aichat.service;

import com.lx.governance.aichat.document.ChatHistoryDocument;
import com.lx.governance.aichat.repository.ChatHistorySearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 对话历史搜索服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistorySearchService {

    private final ChatHistorySearchRepository searchRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    /**
     * 全文搜索对话历史
     */
    public Page<ChatHistoryDocument> searchHistory(String customerId, String keyword, int page, int size) {
        log.info("搜索对话历史 - customerId: {}, keyword: {}", customerId, keyword);

        PageRequest pageRequest = PageRequest.of(page, size);
        return searchRepository.findByCustomerIdAndUserMessageContainingOrAiResponseContaining(
                customerId, keyword, keyword, pageRequest);
    }

    /**
     * 查找相似对话（基于文本相似度）
     */
    public List<ChatHistoryDocument> findSimilarConversations(String customerId, String message, int limit) {
        log.info("查找相似对话 - customerId: {}, message: {}", customerId, message);

        try {
            // 使用简单的文本匹配查询（More Like This 需要更复杂的配置）
            // 这里先实现基础版本，提取关键词进行搜索
            Criteria criteria = new Criteria("customerId").is(customerId)
                    .and(new Criteria("userMessage").contains(message)
                            .or("aiResponse").contains(message));

            CriteriaQuery query = new CriteriaQuery(criteria);
            query.setPageable(PageRequest.of(0, limit));

            SearchHits<ChatHistoryDocument> searchHits = elasticsearchOperations.search(
                    query, ChatHistoryDocument.class);

            return searchHits.stream()
                    .map(SearchHit::getContent)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("查找相似对话失败 - customerId: {}", customerId, e);
            return List.of();
        }
    }

    /**
     * 保存对话到 ES
     */
    public void saveToEs(ChatHistoryDocument document) {
        try {
            searchRepository.save(document);
            log.debug("对话已同步到 ES - id: {}", document.getId());
        } catch (Exception e) {
            log.error("保存对话到 ES 失败 - id: {}", document.getId(), e);
            throw e;
        }
    }
}
