package com.lx.governance.knowledgebase.service;

import com.lx.governance.knowledgebase.domain.DocumentChunk;
import com.lx.governance.knowledgebase.domain.ParsedDocument;
import com.lx.governance.knowledgebase.dto.UploadDocumentRequest;
import com.lx.governance.knowledgebase.dto.MetadataExtractionResult;
import com.lx.governance.knowledgebase.dto.SemanticDedupResult;
import com.lx.governance.knowledgebase.entity.KnowledgeBase;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;
import com.lx.governance.knowledgebase.parser.DocumentParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 文档处理服务
 * 负责文档上传、解析、切片、向量化的完整流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessingService {
    private static final String STAGE_HASH_DEDUP = "HASH_DEDUP";
    private static final String STAGE_PARSING = "PARSING";
    private static final String STAGE_METADATA_EXTRACTION = "METADATA_EXTRACTION";
    private static final String STAGE_SEMANTIC_DEDUP = "SEMANTIC_DEDUP";
    private static final String STAGE_CHUNK_PERSIST = "CHUNK_PERSIST";
    private static final String STAGE_VECTOR_INDEXING = "VECTOR_INDEXING";

    private final List<DocumentParser> documentParsers;
    private final DocumentService documentService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final SemanticChunkingService chunkingService;
    private final VectorStoreService vectorStoreService;
    private final DocumentParseResultService parseResultService;
    private final DocumentChunkService documentChunkService;
    private final DocumentMetadataExtractionService metadataExtractionService;
    private final DocumentSemanticDedupService semanticDedupService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Value("${knowledge-base.storage.upload-path:/data/kb/uploads}")
    private String uploadBasePath;

    @Value("${knowledge-base.document.max-file-size:20971520}")
    private long maxFileSize;

    private static final long ASYNC_THRESHOLD = 5 * 1024 * 1024; // 5MB

    /**
     * 上传并处理文档
     */
    public KnowledgeDocument uploadDocument(String kbId, UploadDocumentRequest request) throws Exception {
        MultipartFile file = request.getFile();
        // 验证知识库存在
        KnowledgeBase kb = knowledgeBaseService.getByKbId(kbId);
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: " + kbId);
        }

        // 验证文件
        validateFile(file);

        // 获取文件类型
        String originalFilename = file.getOriginalFilename();
        String fileType = getFileExtension(originalFilename);

        // 查找对应的解析器
        DocumentParser parser = findParser(fileType);
        if (parser == null) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileType);
        }

        // 保存文件
        File savedFile = saveFile(kbId, file);
        String contentHash = calculateFileHash(savedFile.toPath());

        // 创建文档记录
        KnowledgeDocument document = documentService.createDocument(
                kbId,
                request,
                originalFilename,
                fileType,
                file.getSize(),
                savedFile.getAbsolutePath(),
                contentHash
        );

        documentService.updateProcessingStage(document.getDocId(), STAGE_HASH_DEDUP, 10);
        KnowledgeDocument duplicatedDocument = documentService.findActiveByContentHash(kbId, contentHash);
        if (duplicatedDocument != null && !duplicatedDocument.getDocId().equals(document.getDocId())) {
            documentService.updateStatusToDeduplicated(document.getDocId(), duplicatedDocument.getDocId(), 1.0);
            parseResultService.saveSuccessResult(
                    document.getDocId(),
                    "EXACT_DUPLICATE",
                    "",
                    duplicatedDocument.getTags()
            );
            return documentService.getByDocId(document.getDocId());
        }

        // 判断同步还是异步处理
        if (file.getSize() >= ASYNC_THRESHOLD) {
            // 异步处理
            log.info("文档大小 {} 字节，使用异步处理", file.getSize());
            processDocumentAsync(document, kb, savedFile, parser);
            return document;
        } else {
            // 同步处理
            log.info("文档大小 {} 字节，使用同步处理", file.getSize());
            processDocument(document, kb, savedFile, parser);
            return documentService.getByDocId(document.getDocId());
        }
    }

    public void deleteDocument(String kbId, String docId) throws IOException {
        KnowledgeDocument document = documentService.getByDocId(docId);
        if (document == null || !kbId.equals(document.getKbId())) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }

        if ("ACTIVE".equals(document.getStatus()) && document.getChunkCount() != null && document.getChunkCount() > 0) {
            KnowledgeBase kb = knowledgeBaseService.getByKbId(kbId);
            if (kb != null) {
                vectorStoreService.deleteChunks(kb.getMilvusCollection(), buildChunkIds(docId, document.getChunkCount()));
            }
            documentChunkService.deleteByDocId(docId);
            knowledgeBaseService.decrementDocumentCount(kbId);
            knowledgeBaseService.decrementChunkCount(kbId, document.getChunkCount());
        }

        parseResultService.deleteByDocId(docId);
        documentChunkService.deleteByDocId(docId);
        deleteFileIfExists(document.getFilePath());
        documentService.deleteDocument(docId);
    }

    public void archiveDocument(String kbId, String docId) {
        KnowledgeDocument document = documentService.getByDocId(docId);
        if (document == null || !kbId.equals(document.getKbId())) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }
        if (!"ACTIVE".equals(document.getStatus())) {
            throw new IllegalArgumentException("仅 ACTIVE 文档可归档");
        }

        KnowledgeBase kb = knowledgeBaseService.getByKbId(kbId);
        if (kb != null && document.getChunkCount() != null && document.getChunkCount() > 0) {
            vectorStoreService.deleteChunks(kb.getMilvusCollection(), buildChunkIds(docId, document.getChunkCount()));
            knowledgeBaseService.decrementDocumentCount(kbId);
            knowledgeBaseService.decrementChunkCount(kbId, document.getChunkCount());
        }

        documentChunkService.markArchived(docId);
        documentService.archiveDocument(docId);
    }

    public void activateDocument(String kbId, String docId) {
        KnowledgeDocument document = documentService.getByDocId(docId);
        if (document == null || !kbId.equals(document.getKbId())) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }
        if (!"ARCHIVED".equals(document.getStatus())) {
            throw new IllegalArgumentException("仅 ARCHIVED 文档可激活");
        }

        KnowledgeBase kb = knowledgeBaseService.getByKbId(kbId);
        List<DocumentChunk> chunks = documentChunkService.toDomainChunks(docId);
        if (kb != null && !chunks.isEmpty()) {
            vectorStoreService.insertChunks(kb.getMilvusCollection(), chunks);
            knowledgeBaseService.incrementDocumentCount(kbId);
            knowledgeBaseService.updateChunkCount(kbId, chunks.size());
        }

        documentChunkService.markActive(docId);
        documentService.activateDocument(docId, chunks.size());
    }

    public KnowledgeDocument reprocessDocument(String kbId, String docId) throws Exception {
        KnowledgeDocument document = documentService.getByDocId(docId);
        if (document == null || !kbId.equals(document.getKbId())) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }
        if ("PROCESSING".equals(document.getStatus())) {
            throw new IllegalArgumentException("文档正在处理中，不能重试");
        }
        if ("ACTIVE".equals(document.getStatus()) || "ARCHIVED".equals(document.getStatus())) {
            throw new IllegalArgumentException("当前状态不支持重试: " + document.getStatus());
        }

        KnowledgeBase kb = knowledgeBaseService.getByKbId(kbId);
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: " + kbId);
        }

        File file = Path.of(document.getFilePath()).toFile();
        if (!file.exists()) {
            throw new IllegalArgumentException("原始文件不存在，无法重试");
        }

        DocumentParser parser = findParser(document.getFileType());
        if (parser == null) {
            throw new IllegalArgumentException("不支持的文件类型: " + document.getFileType());
        }

        if (document.getChunkCount() != null && document.getChunkCount() > 0) {
            vectorStoreService.deleteChunks(kb.getMilvusCollection(), buildChunkIds(docId, document.getChunkCount()));
            documentChunkService.deleteByDocId(docId);
        }

        documentService.incrementRetryCount(docId);
        documentService.prepareForReprocess(docId);
        KnowledgeDocument refreshed = documentService.getByDocId(docId);
        processDocument(refreshed, kb, file, parser);
        return documentService.getByDocId(docId);
    }

    /**
     * 同步处理文档
     */
    private void processDocument(
            KnowledgeDocument document,
            KnowledgeBase kb,
            File file,
            DocumentParser parser) {

        try {
            documentService.updateStatusToProcessing(document.getDocId());
            documentService.updateProcessingStage(document.getDocId(), STAGE_PARSING, 25);

            // 解析文档
            ParsedDocument parsedDocument = parser.parse(file, document.getDocId());

            // 语义切片
            List<DocumentChunk> chunks = chunkingService.chunkDocument(
                    parsedDocument,
                    kb.getChunkSize(),
                    kb.getChunkOverlap(),
                    kb.getSemanticThreshold()
            );

            documentService.updateProcessingStage(document.getDocId(), STAGE_METADATA_EXTRACTION, 45);
            MetadataExtractionResult extractionResult = metadataExtractionService.extract(document, parsedDocument);
            documentService.updateProcessingStage(document.getDocId(), STAGE_SEMANTIC_DEDUP, 60);
            SemanticDedupResult dedupResult = semanticDedupService.detectDuplicate(
                    document,
                    extractionResult.getSummary()
            );
            if (dedupResult != null && dedupResult.isDuplicate()) {
                parseResultService.saveSuccessResult(
                        document.getDocId(),
                        extractionResult.getSummary(),
                        joinList(extractionResult.getKeywords()),
                        joinList(extractionResult.getSuggestedTags()),
                        writeJson(extractionResult.getExtendedMetadata()),
                        writeJson(dedupResult)
                );
                documentService.updateStatusToSuspectedDuplicate(
                        document.getDocId(),
                        dedupResult.getMatchedDocId(),
                        dedupResult.getScore()
                );
                log.info("文档命中语义去重: docId={}, matched={}, score={}",
                        document.getDocId(), dedupResult.getMatchedDocId(), dedupResult.getScore());
                return;
            }

            documentService.updateProcessingStage(document.getDocId(), STAGE_CHUNK_PERSIST, 75);
            enrichChunkMetadata(document, chunks);
            document.setIsActive(true);
            document.setIsArchived(false);
            document.setDuplicateStatus("NONE");
            documentChunkService.replaceChunks(document, chunks);
            parseResultService.saveSuccessResult(
                    document.getDocId(),
                    extractionResult.getSummary(),
                    joinList(extractionResult.getKeywords()),
                    joinList(extractionResult.getSuggestedTags()),
                    writeJson(extractionResult.getExtendedMetadata()),
                    null
            );

            // 插入向量（VectorStore 会自动创建集合）
            String collectionName = kb.getMilvusCollection();
            documentService.updateProcessingStage(document.getDocId(), STAGE_VECTOR_INDEXING, 90);
            if (chunks.isEmpty()) {
                log.warn("没有生成任何切片，跳过向量存储");
            } else {
                log.info("开始向量化和存储，集合: {}, 切片数: {}", collectionName, chunks.size());
                vectorStoreService.insertChunks(collectionName, chunks);
            }

            // 更新状态
            documentService.updateStatusToActive(document.getDocId(), chunks.size());
            knowledgeBaseService.incrementDocumentCount(kb.getKbId());
            knowledgeBaseService.updateChunkCount(kb.getKbId(), chunks.size());

            log.info("文档处理完成: docId={}, 切片数={}", document.getDocId(), chunks.size());

        } catch (Exception e) {
            log.error("文档处理失败: docId=" + document.getDocId(), e);
            parseResultService.saveFailureResult(document.getDocId(), e.getMessage());
            documentService.updateStatusToParseFailed(
                    document.getDocId(),
                    e.getMessage(),
                    documentService.getByDocId(document.getDocId()).getProcessingStage()
            );
            throw new RuntimeException("文档处理失败", e);
        }
    }

    /**
     * 异步处理文档
     */
    @Async
    public void processDocumentAsync(
            KnowledgeDocument document,
            KnowledgeBase kb,
            File file,
            DocumentParser parser) {

        try {
            documentService.updateStatusToProcessing(document.getDocId());
            documentService.updateProcessingStage(document.getDocId(), STAGE_PARSING, 25);

            // 解析文档
            ParsedDocument parsedDocument = parser.parse(file, document.getDocId());

            // 语义切片
            List<DocumentChunk> chunks = chunkingService.chunkDocument(
                    parsedDocument,
                    kb.getChunkSize(),
                    kb.getChunkOverlap(),
                    kb.getSemanticThreshold()
            );

            documentService.updateProcessingStage(document.getDocId(), STAGE_METADATA_EXTRACTION, 45);
            MetadataExtractionResult extractionResult = metadataExtractionService.extract(document, parsedDocument);
            documentService.updateProcessingStage(document.getDocId(), STAGE_SEMANTIC_DEDUP, 60);
            SemanticDedupResult dedupResult = semanticDedupService.detectDuplicate(
                    document,
                    extractionResult.getSummary()
            );
            if (dedupResult != null && dedupResult.isDuplicate()) {
                parseResultService.saveSuccessResult(
                        document.getDocId(),
                        extractionResult.getSummary(),
                        joinList(extractionResult.getKeywords()),
                        joinList(extractionResult.getSuggestedTags()),
                        writeJson(extractionResult.getExtendedMetadata()),
                        writeJson(dedupResult)
                );
                documentService.updateStatusToSuspectedDuplicate(
                        document.getDocId(),
                        dedupResult.getMatchedDocId(),
                        dedupResult.getScore()
                );
                log.info("文档异步命中语义去重: docId={}, matched={}, score={}",
                        document.getDocId(), dedupResult.getMatchedDocId(), dedupResult.getScore());
                return;
            }

            documentService.updateProcessingStage(document.getDocId(), STAGE_CHUNK_PERSIST, 75);
            enrichChunkMetadata(document, chunks);
            document.setIsActive(true);
            document.setIsArchived(false);
            document.setDuplicateStatus("NONE");
            documentChunkService.replaceChunks(document, chunks);
            parseResultService.saveSuccessResult(
                    document.getDocId(),
                    extractionResult.getSummary(),
                    joinList(extractionResult.getKeywords()),
                    joinList(extractionResult.getSuggestedTags()),
                    writeJson(extractionResult.getExtendedMetadata()),
                    null
            );

            // 插入向量（VectorStore 会自动创建集合）
            String collectionName = kb.getMilvusCollection();
            documentService.updateProcessingStage(document.getDocId(), STAGE_VECTOR_INDEXING, 90);
            if (chunks.isEmpty()) {
                log.warn("没有生成任何切片，跳过向量存储");
            } else {
                log.info("开始向量化和存储，集合: {}, 切片数: {}", collectionName, chunks.size());
                vectorStoreService.insertChunks(collectionName, chunks);
            }

            // 更新状态
            documentService.updateStatusToActive(document.getDocId(), chunks.size());
            knowledgeBaseService.incrementDocumentCount(kb.getKbId());
            knowledgeBaseService.updateChunkCount(kb.getKbId(), chunks.size());

            log.info("文档异步处理完成: docId={}, 切片数={}", document.getDocId(), chunks.size());

        } catch (Exception e) {
            log.error("文档异步处理失败: docId=" + document.getDocId(), e);
            parseResultService.saveFailureResult(document.getDocId(), e.getMessage());
            documentService.updateStatusToParseFailed(
                    document.getDocId(),
                    e.getMessage(),
                    documentService.getByDocId(document.getDocId()).getProcessingStage()
            );
        }
    }

    /**
     * 验证文件
     */
    private void validateFile(MultipartFile file) {
        if (file == null) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        if (file.getSize() > maxFileSize) {
            throw new IllegalArgumentException("文件大小超过限制: " + maxFileSize + " 字节");
        }
    }

    /**
     * 保存文件
     */
    private File saveFile(String kbId, MultipartFile file) throws IOException {
        // 创建目录
        Path uploadDir = Paths.get(uploadBasePath, kbId);
        Files.createDirectories(uploadDir);

        // 生成文件名
        String originalFilename = file.getOriginalFilename();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String savedFilename = timestamp + "_" + originalFilename;

        Path filePath = uploadDir.resolve(savedFilename);
        file.transferTo(filePath.toFile());

        log.info("文件保存成功: {}", filePath);
        return filePath.toFile();
    }

    /**
     * 查找解析器
     */
    private DocumentParser findParser(String fileType) {
        return documentParsers.stream()
                .filter(parser -> parser.supports(fileType))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    private List<String> buildChunkIds(String docId, int chunkCount) {
        List<String> chunkIds = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            chunkIds.add(docId + "_" + i);
        }
        return chunkIds;
    }

    private void deleteFileIfExists(String filePath) throws IOException {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        Files.deleteIfExists(Path.of(filePath));
    }

    private String calculateFileHash(Path filePath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void enrichChunkMetadata(KnowledgeDocument document, List<DocumentChunk> chunks) {
        chunks.forEach(chunk -> {
            chunk.getMetadata().put("title", document.getTitle());
            chunk.getMetadata().put("tags", document.getTags());
            chunk.getMetadata().put("business_domain", document.getBusinessDomain());
            chunk.getMetadata().put("source", document.getSource());
            chunk.getMetadata().put("applicable_scope", document.getApplicableScope());
            chunk.getMetadata().put("trust_level", document.getTrustLevel());
            chunk.getMetadata().put("is_active", true);
            chunk.getMetadata().put("is_archived", false);
            chunk.getMetadata().put("duplicate_status", "NONE");
        });
    }

    private String joinList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(",", values);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
