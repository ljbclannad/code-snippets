package com.lx.governance.aichat.service;

import com.lx.governance.aichat.document.ChatHistoryDocument;
import com.lx.governance.aichat.repository.ChatHistorySearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ChatHistorySearchService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class ChatHistorySearchServiceTest {

    @Mock
    private ChatHistorySearchRepository searchRepository;

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @InjectMocks
    private ChatHistorySearchService searchService;

    private ChatHistoryDocument testDocument;

    @BeforeEach
    void setUp() {
        testDocument = ChatHistoryDocument.builder()
                .id("1")
                .customerId("customer-123")
                .conversationId("conv-456")
                .userMessage("如何使用 Spring Boot")
                .aiResponse("Spring Boot 是一个快速开发框架...")
                .createTime(LocalDateTime.now())
                .build();
    }

    @Test
    void testSaveToEs_Success() {
        // Given
        when(searchRepository.save(any(ChatHistoryDocument.class)))
                .thenReturn(testDocument);

        // When
        searchService.saveToEs(testDocument);

        // Then
        verify(searchRepository, times(1)).save(testDocument);
    }

    @Test
    void testSaveToEs_ThrowsException() {
        // Given
        when(searchRepository.save(any(ChatHistoryDocument.class)))
                .thenThrow(new RuntimeException("ES 连接失败"));

        // When & Then
        try {
            searchService.saveToEs(testDocument);
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).isEqualTo("ES 连接失败");
        }

        verify(searchRepository, times(1)).save(testDocument);
    }

    @Test
    void testSearchHistory_Success() {
        // Given
        String customerId = "customer-123";
        String keyword = "Spring Boot";
        int page = 0;
        int size = 10;

        List<ChatHistoryDocument> documents = List.of(testDocument);
        Page<ChatHistoryDocument> pageResult = new PageImpl<>(documents);

        when(searchRepository.findByCustomerIdAndUserMessageContainingOrAiResponseContaining(
                eq(customerId), eq(keyword), eq(keyword), any(PageRequest.class)))
                .thenReturn(pageResult);

        // When
        Page<ChatHistoryDocument> result = searchService.searchHistory(customerId, keyword, page, size);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo("1");
        assertThat(result.getContent().get(0).getCustomerId()).isEqualTo(customerId);

        verify(searchRepository, times(1))
                .findByCustomerIdAndUserMessageContainingOrAiResponseContaining(
                        eq(customerId), eq(keyword), eq(keyword), any(PageRequest.class));
    }

    @Test
    void testFindSimilarConversations_Success() {
        // Given
        String customerId = "customer-123";
        String message = "Spring Boot 使用";
        int limit = 3;

        SearchHit<ChatHistoryDocument> searchHit = mock(SearchHit.class);
        when(searchHit.getContent()).thenReturn(testDocument);

        SearchHits<ChatHistoryDocument> searchHits = mock(SearchHits.class);
        when(searchHits.stream()).thenReturn(List.of(searchHit).stream());

        when(elasticsearchOperations.search(any(CriteriaQuery.class), eq(ChatHistoryDocument.class)))
                .thenReturn(searchHits);

        // When
        List<ChatHistoryDocument> result = searchService.findSimilarConversations(customerId, message, limit);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("1");

        verify(elasticsearchOperations, times(1))
                .search(any(CriteriaQuery.class), eq(ChatHistoryDocument.class));
    }

    @Test
    void testFindSimilarConversations_ReturnsEmptyOnException() {
        // Given
        String customerId = "customer-123";
        String message = "Spring Boot 使用";
        int limit = 3;

        when(elasticsearchOperations.search(any(CriteriaQuery.class), eq(ChatHistoryDocument.class)))
                .thenThrow(new RuntimeException("ES 查询失败"));

        // When
        List<ChatHistoryDocument> result = searchService.findSimilarConversations(customerId, message, limit);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();

        verify(elasticsearchOperations, times(1))
                .search(any(CriteriaQuery.class), eq(ChatHistoryDocument.class));
    }
}
