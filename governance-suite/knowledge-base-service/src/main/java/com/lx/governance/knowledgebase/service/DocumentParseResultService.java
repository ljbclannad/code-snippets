package com.lx.governance.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.knowledgebase.entity.KnowledgeDocumentParseResult;
import com.lx.governance.knowledgebase.mapper.KnowledgeDocumentParseResultMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DocumentParseResultService {

    private final KnowledgeDocumentParseResultMapper parseResultMapper;

    @Transactional(rollbackFor = Exception.class)
    public void saveSuccessResult(String docId, String summary, String keywords, String suggestedTags) {
        saveSuccessResult(docId, summary, keywords, suggestedTags, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveSuccessResult(String docId,
                                  String summary,
                                  String keywords,
                                  String suggestedTags,
                                  String extendedMetadata,
                                  String semanticDedupResult) {
        KnowledgeDocumentParseResult result = getByDocId(docId);
        if (result == null) {
            result = new KnowledgeDocumentParseResult();
            result.setDocId(docId);
        }
        result.setParseStatus("SUCCESS");
        result.setSummary(summary);
        result.setKeywords(keywords);
        result.setSuggestedTags(suggestedTags);
        result.setExtendedMetadata(extendedMetadata);
        result.setSemanticDedupResult(semanticDedupResult);
        result.setProcessedAt(LocalDateTime.now());
        result.setParseErrorMessage(null);
        upsert(result);
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveFailureResult(String docId, String errorMessage) {
        KnowledgeDocumentParseResult result = getByDocId(docId);
        if (result == null) {
            result = new KnowledgeDocumentParseResult();
            result.setDocId(docId);
        }
        result.setParseStatus("FAILED");
        result.setParseErrorMessage(errorMessage);
        result.setProcessedAt(LocalDateTime.now());
        upsert(result);
    }

    public KnowledgeDocumentParseResult getByDocId(String docId) {
        LambdaQueryWrapper<KnowledgeDocumentParseResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocumentParseResult::getDocId, docId);
        return parseResultMapper.selectOne(wrapper);
    }

    public Map<String, KnowledgeDocumentParseResult> getByDocIds(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<KnowledgeDocumentParseResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(KnowledgeDocumentParseResult::getDocId, docIds);
        return parseResultMapper.selectList(wrapper).stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeDocumentParseResult::getDocId, result -> result));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocId(String docId) {
        LambdaQueryWrapper<KnowledgeDocumentParseResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocumentParseResult::getDocId, docId);
        parseResultMapper.delete(wrapper);
    }

    private void upsert(KnowledgeDocumentParseResult result) {
        if (result.getId() == null) {
            parseResultMapper.insert(result);
        } else {
            parseResultMapper.updateById(result);
        }
    }
}
