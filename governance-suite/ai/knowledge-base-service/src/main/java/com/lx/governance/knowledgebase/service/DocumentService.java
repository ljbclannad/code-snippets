package com.lx.governance.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.knowledgebase.dto.UploadDocumentRequest;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;
import com.lx.governance.knowledgebase.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文档管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final KnowledgeDocumentMapper documentMapper;

    /**
     * 创建文档记录
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocument createDocument(
            String kbId,
            UploadDocumentRequest request,
            String fileName,
            String fileType,
            Long fileSize,
            String filePath,
            String contentHash) {

        String docId = "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        KnowledgeDocument document = new KnowledgeDocument();
        document.setKbId(kbId);
        document.setDocId(docId);
        document.setTitle(request.getTitle());
        document.setTags(request.getTags());
        document.setBusinessDomain(request.getBusinessDomain());
        document.setSource(request.getSource());
        document.setApplicableScope(request.getApplicableScope());
        document.setTrustLevel(request.getTrustLevel());
        document.setFileName(fileName);
        document.setFileType(fileType);
        document.setFileSize(fileSize);
        document.setFilePath(filePath);
        document.setContentHash(contentHash);
        document.setStatus("UPLOADED");
        document.setIsActive(false);
        document.setIsArchived(false);
        document.setDuplicateStatus("NONE");
        document.setChunkCount(0);
        document.setProcessingStage("UPLOADED");
        document.setProcessingProgress(0);
        document.setRetryCount(0);
        document.setCreatedBy(request.getCreatedBy());
        document.setCreateTime(LocalDateTime.now());
        document.setUpdateTime(LocalDateTime.now());
        document.setUploadTime(LocalDateTime.now());

        documentMapper.insert(document);
        log.info("创建文档记录: docId={}, fileName={}", docId, fileName);

        return document;
    }

    /**
     * 更新文档状态为处理中
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatusToProcessing(String docId) {
        KnowledgeDocument document = getByDocId(docId);
        if (document != null) {
            document.setStatus("PROCESSING");
            document.setProcessStartTime(LocalDateTime.now());
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateProcessingStage(String docId, String processingStage, int progress) {
        KnowledgeDocument document = getByDocId(docId);
        if (document != null) {
            document.setProcessingStage(processingStage);
            document.setProcessingProgress(progress);
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
        }
    }

    /**
     * 更新文档状态为完成
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatusToActive(String docId, int chunkCount) {
        KnowledgeDocument document = getByDocId(docId);
        if (document != null) {
            document.setStatus("ACTIVE");
            document.setIsActive(true);
            document.setChunkCount(chunkCount);
            document.setProcessEndTime(LocalDateTime.now());
            document.setProcessingStage("COMPLETED");
            document.setProcessingProgress(100);
            document.setLastErrorStage(null);
            document.setErrorMessage(null);
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
        }
    }

    /**
     * 更新文档状态为失败
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatusToFailed(String docId, String errorMessage) {
        KnowledgeDocument document = getByDocId(docId);
        if (document != null) {
            document.setStatus("FAILED");
            document.setErrorMessage(errorMessage);
            document.setProcessEndTime(LocalDateTime.now());
            document.setIsActive(false);
            document.setLastErrorStage("FAILED");
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStatusToParseFailed(String docId, String errorMessage) {
        updateStatusToParseFailed(docId, errorMessage, "PARSING");
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStatusToParseFailed(String docId, String errorMessage, String errorStage) {
        KnowledgeDocument document = getByDocId(docId);
        if (document != null) {
            document.setStatus("PARSE_FAILED");
            document.setErrorMessage(errorMessage);
            document.setProcessEndTime(LocalDateTime.now());
            document.setIsActive(false);
            document.setLastErrorStage(errorStage);
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStatusToDeduplicated(String docId, String targetDocId, Double duplicateScore) {
        KnowledgeDocument document = getByDocId(docId);
        if (document != null) {
            document.setStatus("DEDUPLICATED");
            document.setDuplicateStatus("EXACT_DUPLICATE");
            document.setDuplicateTargetDocId(targetDocId);
            document.setDuplicateScore(duplicateScore);
            document.setProcessEndTime(LocalDateTime.now());
            document.setIsActive(false);
            document.setProcessingStage("HASH_DEDUP");
            document.setProcessingProgress(100);
            document.setLastErrorStage(null);
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
        }
    }

    /**
     * 根据 docId 查询文档
     */
    public KnowledgeDocument getByDocId(String docId) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getDocId, docId);
        return documentMapper.selectOne(wrapper);
    }

    /**
     * 查询知识库下的所有文档
     */
    public List<KnowledgeDocument> listByKbId(String kbId) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getKbId, kbId);
        wrapper.orderByDesc(KnowledgeDocument::getUploadTime);
        return documentMapper.selectList(wrapper);
    }

    public KnowledgeDocument findActiveByContentHash(String kbId, String contentHash) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getKbId, kbId);
        wrapper.eq(KnowledgeDocument::getContentHash, contentHash);
        wrapper.eq(KnowledgeDocument::getIsActive, true);
        wrapper.eq(KnowledgeDocument::getIsArchived, false);
        wrapper.ne(KnowledgeDocument::getStatus, "ARCHIVED");
        wrapper.last("LIMIT 1");
        return documentMapper.selectOne(wrapper);
    }

    public KnowledgeDocument findLatestBySourceAndFileName(String kbId, String source, String fileName) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getKbId, kbId);
        wrapper.eq(KnowledgeDocument::getSource, source);
        wrapper.eq(KnowledgeDocument::getFileName, fileName);
        wrapper.orderByDesc(KnowledgeDocument::getUpdateTime);
        wrapper.last("LIMIT 1");
        return documentMapper.selectOne(wrapper);
    }

    public List<KnowledgeDocument> listActiveByKbId(String kbId, String excludeDocId, int limit) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getKbId, kbId);
        wrapper.eq(KnowledgeDocument::getIsActive, true);
        wrapper.eq(KnowledgeDocument::getIsArchived, false);
        wrapper.eq(KnowledgeDocument::getDuplicateStatus, "NONE");
        wrapper.eq(KnowledgeDocument::getStatus, "ACTIVE");
        if (excludeDocId != null && !excludeDocId.isBlank()) {
            wrapper.ne(KnowledgeDocument::getDocId, excludeDocId);
        }
        wrapper.orderByDesc(KnowledgeDocument::getUpdateTime);
        wrapper.last("LIMIT " + limit);
        return documentMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStatusToSuspectedDuplicate(String docId, String targetDocId, Double duplicateScore) {
        KnowledgeDocument document = getByDocId(docId);
        if (document != null) {
            document.setStatus("SUSPECTED_DUPLICATE");
            document.setDuplicateStatus("SUSPECTED_DUPLICATE");
            document.setDuplicateTargetDocId(targetDocId);
            document.setDuplicateScore(duplicateScore);
            document.setProcessEndTime(LocalDateTime.now());
            document.setIsActive(false);
            document.setProcessingStage("SEMANTIC_DEDUP");
            document.setProcessingProgress(100);
            document.setLastErrorStage(null);
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void archiveDocument(String docId) {
        KnowledgeDocument document = getByDocId(docId);
        if (document != null) {
            document.setStatus("ARCHIVED");
            document.setIsActive(false);
            document.setIsArchived(true);
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void activateDocument(String docId, int chunkCount) {
        KnowledgeDocument document = getByDocId(docId);
        if (document != null) {
            document.setStatus("ACTIVE");
            document.setIsActive(true);
            document.setIsArchived(false);
            document.setDuplicateStatus("NONE");
            document.setChunkCount(chunkCount);
            document.setProcessingStage("COMPLETED");
            document.setProcessingProgress(100);
            document.setLastErrorStage(null);
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateDocumentTags(String docId, String tags, String updatedBy) {
        KnowledgeDocument document = getByDocId(docId);
        if (document == null) {
            return;
        }
        document.setTags(tags);
        if (updatedBy != null && !updatedBy.isBlank()) {
            document.setCreatedBy(updatedBy);
        }
        document.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(document);
    }

    @Transactional(rollbackFor = Exception.class)
    public void incrementRetryCount(String docId) {
        KnowledgeDocument document = getByDocId(docId);
        if (document != null) {
            document.setRetryCount((document.getRetryCount() == null ? 0 : document.getRetryCount()) + 1);
            document.setLastRetryTime(LocalDateTime.now());
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void prepareForReprocess(String docId) {
        KnowledgeDocument document = getByDocId(docId);
        if (document != null) {
            document.setStatus("UPLOADED");
            document.setIsActive(false);
            document.setIsArchived(false);
            document.setDuplicateStatus("NONE");
            document.setDuplicateTargetDocId(null);
            document.setDuplicateScore(null);
            document.setChunkCount(0);
            document.setProcessStartTime(null);
            document.setProcessEndTime(null);
            document.setProcessingStage("UPLOADED");
            document.setProcessingProgress(0);
            document.setLastErrorStage(null);
            document.setErrorMessage(null);
            document.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(document);
        }
    }

    /**
     * 删除文档
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(String docId) {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getDocId, docId);
        documentMapper.delete(wrapper);
        log.info("删除文档: {}", docId);
    }
}
