package com.lx.governance.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.knowledgebase.domain.DocumentChunk;
import com.lx.governance.knowledgebase.dto.SearchRequest;
import com.lx.governance.knowledgebase.dto.SearchResult;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;
import com.lx.governance.knowledgebase.entity.KnowledgeDocumentChunk;
import com.lx.governance.knowledgebase.mapper.KnowledgeDocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocumentChunkService {

    private final KnowledgeDocumentChunkMapper chunkMapper;

    @Transactional(rollbackFor = Exception.class)
    public void replaceChunks(KnowledgeDocument document, List<DocumentChunk> chunks) {
        LambdaQueryWrapper<KnowledgeDocumentChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocumentChunk::getDocId, document.getDocId());
        chunkMapper.delete(wrapper);

        List<KnowledgeDocumentChunk> rows = chunks.stream()
                .map(chunk -> toEntity(document, chunk))
                .collect(Collectors.toList());

        rows.forEach(chunkMapper::insert);
    }

    @Transactional(readOnly = true)
    public List<SearchResult> keywordSearch(String kbId, SearchRequest request, int limit) {
        LambdaQueryWrapper<KnowledgeDocumentChunk> wrapper = buildFilterWrapper(kbId, request);
        wrapper.and(w -> w.like(KnowledgeDocumentChunk::getTitle, request.getQuery())
                .or()
                .like(KnowledgeDocumentChunk::getContent, request.getQuery()));
        wrapper.last("LIMIT " + limit);

        return chunkMapper.selectList(wrapper).stream()
                .map(this::toKeywordSearchResult)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentChunk> listByDocId(String docId) {
        LambdaQueryWrapper<KnowledgeDocumentChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocumentChunk::getDocId, docId);
        wrapper.orderByAsc(KnowledgeDocumentChunk::getChunkIndex);
        return chunkMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocId(String docId) {
        LambdaQueryWrapper<KnowledgeDocumentChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocumentChunk::getDocId, docId);
        chunkMapper.delete(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markArchived(String docId) {
        listByDocId(docId).forEach(chunk -> {
            chunk.setIsActive(false);
            chunk.setIsArchived(true);
            chunkMapper.updateById(chunk);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void markActive(String docId) {
        listByDocId(docId).forEach(chunk -> {
            chunk.setIsActive(true);
            chunk.setIsArchived(false);
            chunk.setDuplicateStatus("NONE");
            chunkMapper.updateById(chunk);
        });
    }

    @Transactional(readOnly = true)
    public List<DocumentChunk> toDomainChunks(String docId) {
        return listByDocId(docId).stream()
                .map(chunk -> DocumentChunk.builder()
                        .chunkId(chunk.getChunkId())
                        .docId(chunk.getDocId())
                        .chunkIndex(chunk.getChunkIndex())
                        .content(chunk.getContent())
                        .metadata(buildChunkMetadata(chunk))
                        .build())
                .toList();
    }

    private KnowledgeDocumentChunk toEntity(KnowledgeDocument document, DocumentChunk chunk) {
        KnowledgeDocumentChunk row = new KnowledgeDocumentChunk();
        row.setChunkId(chunk.getChunkId());
        row.setKbId(document.getKbId());
        row.setDocId(document.getDocId());
        row.setChunkIndex(chunk.getChunkIndex());
        row.setTitle(document.getTitle());
        row.setTags(document.getTags());
        row.setBusinessDomain(document.getBusinessDomain());
        row.setSource(document.getSource());
        row.setApplicableScope(document.getApplicableScope());
        row.setTrustLevel(document.getTrustLevel());
        row.setIsActive(Boolean.TRUE.equals(document.getIsActive()));
        row.setIsArchived(Boolean.TRUE.equals(document.getIsArchived()));
        row.setDuplicateStatus(document.getDuplicateStatus());
        row.setContent(chunk.getContent());
        return row;
    }

    private LambdaQueryWrapper<KnowledgeDocumentChunk> buildFilterWrapper(String kbId, SearchRequest request) {
        LambdaQueryWrapper<KnowledgeDocumentChunk> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocumentChunk::getKbId, kbId);
        wrapper.eq(KnowledgeDocumentChunk::getIsActive, true);
        wrapper.eq(KnowledgeDocumentChunk::getIsArchived, false);
        wrapper.eq(KnowledgeDocumentChunk::getDuplicateStatus, "NONE");

        if (request.getBusinessDomain() != null && !request.getBusinessDomain().isBlank()) {
            wrapper.eq(KnowledgeDocumentChunk::getBusinessDomain, request.getBusinessDomain());
        }
        if (request.getSource() != null && !request.getSource().isBlank()) {
            wrapper.eq(KnowledgeDocumentChunk::getSource, request.getSource());
        }
        if (request.getApplicableScope() != null && !request.getApplicableScope().isBlank()) {
            wrapper.eq(KnowledgeDocumentChunk::getApplicableScope, request.getApplicableScope());
        }
        if (request.getTrustLevel() != null && !request.getTrustLevel().isBlank()) {
            wrapper.eq(KnowledgeDocumentChunk::getTrustLevel, request.getTrustLevel());
        }
        if (request.getTitleKeyword() != null && !request.getTitleKeyword().isBlank()) {
            wrapper.like(KnowledgeDocumentChunk::getTitle, request.getTitleKeyword());
        }
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            wrapper.and(tagWrapper -> request.getTags().forEach(tag ->
                    tagWrapper.or().like(KnowledgeDocumentChunk::getTags, tag)));
        }
        if (request.getDocIds() != null && !request.getDocIds().isEmpty()) {
            wrapper.in(KnowledgeDocumentChunk::getDocId, request.getDocIds());
        }
        return wrapper;
    }

    private SearchResult toKeywordSearchResult(KnowledgeDocumentChunk chunk) {
        Map<String, Object> metadata = buildChunkMetadata(chunk);

        return SearchResult.builder()
                .chunkId(chunk.getChunkId())
                .docId(chunk.getDocId())
                .title(chunk.getTitle())
                .tags(chunk.getTags())
                .businessDomain(chunk.getBusinessDomain())
                .source(chunk.getSource())
                .applicableScope(chunk.getApplicableScope())
                .trustLevel(chunk.getTrustLevel())
                .content(chunk.getContent())
                .score(0.55)
                .hitReason("keyword")
                .metadata(metadata)
                .build();
    }

    private Map<String, Object> buildChunkMetadata(KnowledgeDocumentChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", chunk.getSource());
        metadata.put("business_domain", chunk.getBusinessDomain());
        metadata.put("trust_level", chunk.getTrustLevel());
        metadata.put("title", chunk.getTitle());
        metadata.put("tags", chunk.getTags());
        metadata.put("applicable_scope", chunk.getApplicableScope());
        metadata.put("is_active", chunk.getIsActive());
        metadata.put("is_archived", chunk.getIsArchived());
        metadata.put("duplicate_status", chunk.getDuplicateStatus());
        return metadata;
    }
}
