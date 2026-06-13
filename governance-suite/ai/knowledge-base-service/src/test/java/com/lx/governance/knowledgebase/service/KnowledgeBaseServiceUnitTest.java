package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.entity.KnowledgeBase;
import com.lx.governance.knowledgebase.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class KnowledgeBaseServiceUnitTest {

    @Test
    void testDeleteKnowledgeBaseDropsMilvusCollection() {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        KnowledgeBaseService service = spy(new KnowledgeBaseService(mapper, vectorStoreService));

        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(1L);
        knowledgeBase.setKbId("kb_001");
        knowledgeBase.setMilvusCollection("kb_kb_001");
        knowledgeBase.setStatus("ACTIVE");

        doReturn(knowledgeBase).when(service).getByKbId("kb_001");

        service.deleteKnowledgeBase("kb_001");

        verify(vectorStoreService).dropCollection("kb_kb_001");
        verify(mapper).updateById(knowledgeBase);
    }
}
