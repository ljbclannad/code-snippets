package com.lx.governance.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.knowledgebase.entity.KnowledgeBase;
import com.lx.governance.knowledgebase.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 知识库服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final VectorStoreService vectorStoreService;

    /**
     * 创建知识库
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBase createKnowledgeBase(
            String name,
            String description,
            Integer chunkSize,
            Integer chunkOverlap,
            Double semanticThreshold,
            String createdBy) {

        String kbId = "kb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String collectionName = "kb_" + kbId;

        KnowledgeBase kb = new KnowledgeBase();
        kb.setKbId(kbId);
        kb.setName(name);
        kb.setDescription(description);
        kb.setChunkSize(chunkSize != null ? chunkSize : 500);
        kb.setChunkOverlap(chunkOverlap != null ? chunkOverlap : 50);
        kb.setSemanticThreshold(semanticThreshold != null ? semanticThreshold : 0.7);
        kb.setMilvusCollection(collectionName);
        kb.setEmbeddingDimension(1024); // Qwen embedding dimension
        kb.setStatus("ACTIVE");
        kb.setDocumentCount(0);
        kb.setChunkCount(0);
        kb.setCreateTime(LocalDateTime.now());
        kb.setUpdateTime(LocalDateTime.now());
        kb.setCreatedBy(createdBy);

        knowledgeBaseMapper.insert(kb);
        log.info("创建知识库成功: {}, kbId: {}", name, kbId);

        return kb;
    }

    /**
     * 根据 kbId 查询知识库
     */
    public KnowledgeBase getByKbId(String kbId) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getKbId, kbId);
        wrapper.eq(KnowledgeBase::getStatus, "ACTIVE");
        return knowledgeBaseMapper.selectOne(wrapper);
    }

    /**
     * 更新文档计数
     */
    @Transactional(rollbackFor = Exception.class)
    public void incrementDocumentCount(String kbId) {
        knowledgeBaseMapper.updateDocumentCount(kbId, 1);
    }

    /**
     * 更新切片计数
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateChunkCount(String kbId, int chunkCount) {
        knowledgeBaseMapper.updateChunkCount(kbId, chunkCount);
    }

    @Transactional(rollbackFor = Exception.class)
    public void decrementDocumentCount(String kbId) {
        knowledgeBaseMapper.updateDocumentCount(kbId, -1);
    }

    @Transactional(rollbackFor = Exception.class)
    public void decrementChunkCount(String kbId, int chunkCount) {
        knowledgeBaseMapper.updateChunkCount(kbId, -chunkCount);
    }

    /**
     * 删除知识库
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeBase(String kbId) {
        KnowledgeBase kb = getByKbId(kbId);
        if (kb != null) {
            vectorStoreService.dropCollection(kb.getMilvusCollection());
            kb.setStatus("DELETED");
            kb.setUpdateTime(LocalDateTime.now());
            knowledgeBaseMapper.updateById(kb);
            log.info("删除知识库: {}", kbId);
        }
    }
}
