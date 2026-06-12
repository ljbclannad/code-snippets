package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.RetrievalQuery;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;

class LayeredMemoryOrchestratorTest {

    @Mock
    private MemoryService memoryService;
    @Mock
    private RetrievalQueryRewriteService retrievalQueryRewriteService;

    private LayeredMemoryOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orchestrator = new LayeredMemoryOrchestrator(memoryService, retrievalQueryRewriteService);
    }

    @Test
    void shouldReturnShortTermMemoriesWithoutLongTermRecallWhenRetrievalSkipped() throws Exception {
        SessionContext context = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .complexity("simple")
                .recentSummaries(List.of(MessageSummary.builder().userMessage("最近血糖偏高").build()))
                .build();

        LayeredMemories memories = orchestrator.loadMemories(
                "group-1",
                "customer-1",
                "我头疼",
                context,
                new UserProfileDTO(),
                true,
                5).get();

        assertEquals(3, memories.getShortTermMemories().size());
        assertTrue(memories.getLongTermMemories().isEmpty());
        verify(memoryService, never()).retrieveMemories(anyString(), anyString(), anyString());
    }

    @Test
    void shouldUseRewriteForLongTermRecallAndKeepShortTermMemories() throws Exception {
        SessionContext context = SessionContext.builder()
                .currentTopic("减重管理")
                .userIntent("饮食建议")
                .complexity("complex")
                .recentSummaries(List.of(MessageSummary.builder().userMessage("最近血糖偏高").build()))
                .build();

        MemoryItemDTO longTerm = new MemoryItemDTO();
        longTerm.setContent("长期记忆");
        longTerm.setSimilarity(0.91);

        when(retrievalQueryRewriteService.rewrite(anyString(), any(), any()))
                .thenReturn(RetrievalQuery.builder()
                        .rewrittenQuery("减重管理 饮食建议 晚饭怎么吃")
                        .build());
        when(memoryService.retrieveMemories(eq("group-1"), eq("customer-1"), eq("减重管理 饮食建议 晚饭怎么吃")))
                .thenReturn(CompletableFuture.completedFuture(List.of(longTerm)));

        LayeredMemories memories = orchestrator.loadMemories(
                "group-1",
                "customer-1",
                "那我这种情况晚饭怎么吃？",
                context,
                new UserProfileDTO(),
                true,
                5).get();

        assertEquals(3, memories.getShortTermMemories().size());
        assertEquals(1, memories.getLongTermMemories().size());
        verify(memoryService).retrieveMemories("group-1", "customer-1", "减重管理 饮食建议 晚饭怎么吃");
    }
}
