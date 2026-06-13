package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.domain.DocumentChunk;
import io.milvus.client.MilvusServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.InitializingBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class VectorStoreServiceTest {

    @Test
    void insertChunksInitializesVectorStoreBeforeAdd() {
        RecordingVectorStore recordingVectorStore = new RecordingVectorStore();
        TestableVectorStoreService service = new TestableVectorStoreService(
                mock(MilvusServiceClient.class),
                mock(EmbeddingModel.class),
                recordingVectorStore
        );

        service.insertChunks("kb_test", List.of(DocumentChunk.builder()
                .chunkId("doc_001_0")
                .docId("doc_001")
                .chunkIndex(0)
                .content("test content")
                .metadata(Map.of("source", "test"))
                .build()));

        assertTrue(recordingVectorStore.initialized, "首次插入前应该初始化 collection");
        assertTrue(recordingVectorStore.addCalled, "初始化后应该执行向量写入");
    }

    private static class TestableVectorStoreService extends VectorStoreService {
        private final RecordingVectorStore vectorStore;

        private TestableVectorStoreService(MilvusServiceClient milvusClient,
                                           EmbeddingModel embeddingModel,
                                           RecordingVectorStore vectorStore) {
            super(milvusClient, embeddingModel);
            this.vectorStore = vectorStore;
        }

        @Override
        protected VectorStore createVectorStore(String collectionName) {
            return vectorStore;
        }
    }

    private static class RecordingVectorStore implements VectorStore, InitializingBean {
        private boolean initialized;
        private boolean addCalled;

        @Override
        public void afterPropertiesSet() {
            this.initialized = true;
        }

        @Override
        public void add(List<Document> documents) {
            if (!initialized) {
                throw new IllegalStateException("vector store not initialized");
            }
            this.addCalled = true;
        }

        @Override
        public Optional<Boolean> delete(List<String> idList) {
            return Optional.of(Boolean.TRUE);
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of();
        }
    }
}
