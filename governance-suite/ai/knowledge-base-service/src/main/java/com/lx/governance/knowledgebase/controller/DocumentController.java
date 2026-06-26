package com.lx.governance.knowledgebase.controller;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.knowledgebase.dto.CrawlJobManifestView;
import com.lx.governance.knowledgebase.dto.CrawlJobSummary;
import com.lx.governance.knowledgebase.dto.CrawlImportRequest;
import com.lx.governance.knowledgebase.dto.CrawlImportResponse;
import com.lx.governance.knowledgebase.dto.DocumentGovernanceUpdateRequest;
import com.lx.governance.knowledgebase.dto.DocumentInfoResponse;
import com.lx.governance.knowledgebase.dto.LocalBatchImportRequest;
import com.lx.governance.knowledgebase.dto.LocalBatchImportResponse;
import com.lx.governance.knowledgebase.dto.UploadDocumentRequest;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;
import com.lx.governance.knowledgebase.entity.KnowledgeDocumentChunk;
import com.lx.governance.knowledgebase.entity.KnowledgeDocumentParseResult;
import com.lx.governance.knowledgebase.service.DocumentProcessingService;
import com.lx.governance.knowledgebase.service.LocalDocumentBatchImportService;
import com.lx.governance.knowledgebase.service.MedicalDocumentCrawlService;
import com.lx.governance.knowledgebase.service.MedicalDocumentGovernanceService;
import com.lx.governance.knowledgebase.service.DocumentChunkService;
import com.lx.governance.knowledgebase.service.DocumentParseResultService;
import com.lx.governance.knowledgebase.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/kb/{kbId}/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentProcessingService documentProcessingService;
    private final DocumentService documentService;
    private final DocumentParseResultService parseResultService;
    private final DocumentChunkService documentChunkService;
    private final MedicalDocumentCrawlService medicalDocumentCrawlService;
    private final LocalDocumentBatchImportService localDocumentBatchImportService;
    private final MedicalDocumentGovernanceService medicalDocumentGovernanceService;

    /**
     * 上传文档
     */
    @PostMapping
    public ApiResponse<DocumentInfoResponse> uploadDocument(
            @PathVariable String kbId,
            @Validated @ModelAttribute UploadDocumentRequest request) {

        log.info("上传文档: kbId={}, 文件名={}", kbId,
                request.getFile() != null ? request.getFile().getOriginalFilename() : null);

        try {
            KnowledgeDocument document = documentProcessingService.uploadDocument(kbId, request);

            DocumentInfoResponse response = new DocumentInfoResponse();
            BeanUtils.copyProperties(document, response);

            return ApiResponse.ok(response, null);

        } catch (IllegalArgumentException e) {
            log.warn("文档上传参数错误: {}", e.getMessage());
            return ApiResponse.fail("INVALID_PARAM", e.getMessage(), null);
        } catch (Exception e) {
            log.error("文档上传失败", e);
            return ApiResponse.fail("UPLOAD_FAILED", "文档上传失败: " + e.getMessage(), null);
        }
    }

    /**
     * 抓取网页并导入为知识库文档。
     */
    @PostMapping("/crawl")
    public ApiResponse<CrawlImportResponse> crawlAndImport(
            @PathVariable String kbId,
            @Validated @RequestBody CrawlImportRequest request) {
        log.info("抓取导入文档: kbId={}, seedUrl={}, maxPages={}", kbId, request.getSeedUrl(), request.getMaxPages());
        try {
            return ApiResponse.ok(medicalDocumentCrawlService.crawlAndImport(kbId, request), null);
        } catch (IllegalArgumentException e) {
            log.warn("抓取导入参数错误: {}", e.getMessage());
            return ApiResponse.fail("INVALID_PARAM", e.getMessage(), null);
        } catch (Exception e) {
            log.error("抓取导入失败: kbId={}, seedUrl={}", kbId, request.getSeedUrl(), e);
            return ApiResponse.fail("CRAWL_IMPORT_FAILED", "抓取导入失败: " + e.getMessage(), null);
        }
    }

    @PostMapping("/local-batch-import")
    public ApiResponse<LocalBatchImportResponse> localBatchImport(
            @PathVariable String kbId,
            @Validated @RequestBody LocalBatchImportRequest request) {
        log.info("本地批量导入文档: kbId={}, manifestPath={}, dryRun={}",
                kbId, request.getManifestPath(), request.getDryRun());
        try {
            return ApiResponse.ok(localDocumentBatchImportService.importFromManifest(kbId, request), null);
        } catch (IllegalArgumentException e) {
            log.warn("本地批量导入参数错误: {}", e.getMessage());
            return ApiResponse.fail("INVALID_PARAM", e.getMessage(), null);
        } catch (Exception e) {
            log.error("本地批量导入失败: kbId={}, manifestPath={}", kbId, request.getManifestPath(), e);
            return ApiResponse.fail("LOCAL_BATCH_IMPORT_FAILED", "本地批量导入失败: " + e.getMessage(), null);
        }
    }

    @GetMapping("/crawl/jobs")
    public ApiResponse<List<CrawlJobSummary>> listCrawlJobs(
            @RequestParam(defaultValue = "20") int limit) {
        try {
            return ApiResponse.ok(medicalDocumentCrawlService.listCrawlJobs(limit), null);
        } catch (Exception e) {
            log.error("查询抓取作业列表失败", e);
            return ApiResponse.fail("CRAWL_JOB_LIST_FAILED", "查询抓取作业列表失败: " + e.getMessage(), null);
        }
    }

    @GetMapping("/crawl/jobs/{jobId}")
    public ApiResponse<CrawlJobManifestView> getCrawlJob(@PathVariable String jobId) {
        try {
            return ApiResponse.ok(medicalDocumentCrawlService.getCrawlJob(jobId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("CRAWL_JOB_NOT_FOUND", e.getMessage(), null);
        } catch (Exception e) {
            log.error("查询抓取作业失败: jobId={}", jobId, e);
            return ApiResponse.fail("CRAWL_JOB_READ_FAILED", "查询抓取作业失败: " + e.getMessage(), null);
        }
    }

    @PostMapping("/crawl/jobs/{jobId}/retry")
    public ApiResponse<CrawlImportResponse> retryCrawlJob(
            @PathVariable String kbId,
            @PathVariable String jobId) {
        try {
            return ApiResponse.ok(medicalDocumentCrawlService.retryCrawlJob(kbId, jobId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("CRAWL_JOB_NOT_FOUND", e.getMessage(), null);
        } catch (Exception e) {
            log.error("重试抓取作业失败: kbId={}, jobId={}", kbId, jobId, e);
            return ApiResponse.fail("CRAWL_JOB_RETRY_FAILED", "重试抓取作业失败: " + e.getMessage(), null);
        }
    }

    /**
     * 查询文档详情
     */
    @GetMapping("/{docId}")
    public ApiResponse<DocumentInfoResponse> getDocument(
            @PathVariable String kbId,
            @PathVariable String docId) {

        log.info("查询文档详情: kbId={}, docId={}", kbId, docId);

        KnowledgeDocument document = documentService.getByDocId(docId);
        if (document == null || !document.getKbId().equals(kbId)) {
            return ApiResponse.fail("DOC_NOT_FOUND", "文档不存在", null);
        }

        DocumentInfoResponse response = new DocumentInfoResponse();
        BeanUtils.copyProperties(document, response);

        return ApiResponse.ok(response, null);
    }

    /**
     * 列出知识库下的所有文档
     */
    @GetMapping
    public ApiResponse<List<DocumentInfoResponse>> listDocuments(@PathVariable String kbId) {
        log.info("列出文档: kbId={}", kbId);

        List<KnowledgeDocument> documents = documentService.listByKbId(kbId);

        List<DocumentInfoResponse> responses = documents.stream()
                .map(doc -> {
                    DocumentInfoResponse response = new DocumentInfoResponse();
                    BeanUtils.copyProperties(doc, response);
                    return response;
                })
                .collect(Collectors.toList());

        return ApiResponse.ok(responses, null);
    }

    @PostMapping("/{docId}/reprocess")
    public ApiResponse<DocumentInfoResponse> reprocessDocument(
            @PathVariable String kbId,
            @PathVariable String docId) {
        try {
            KnowledgeDocument document = documentProcessingService.reprocessDocument(kbId, docId);
            DocumentInfoResponse response = new DocumentInfoResponse();
            BeanUtils.copyProperties(document, response);
            return ApiResponse.ok(response, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("INVALID_STATE", e.getMessage(), null);
        } catch (Exception e) {
            log.error("重处理文档失败: kbId={}, docId={}", kbId, docId, e);
            return ApiResponse.fail("REPROCESS_FAILED", "重处理文档失败: " + e.getMessage(), null);
        }
    }

    @GetMapping("/{docId}/parse-result")
    public ApiResponse<KnowledgeDocumentParseResult> getParseResult(
            @PathVariable String kbId,
            @PathVariable String docId) {
        KnowledgeDocument document = documentService.getByDocId(docId);
        if (document == null || !kbId.equals(document.getKbId())) {
            return ApiResponse.fail("DOC_NOT_FOUND", "文档不存在", null);
        }
        return ApiResponse.ok(parseResultService.getByDocId(docId), null);
    }

    @GetMapping("/{docId}/chunks")
    public ApiResponse<List<KnowledgeDocumentChunk>> getChunks(
            @PathVariable String kbId,
            @PathVariable String docId) {
        KnowledgeDocument document = documentService.getByDocId(docId);
        if (document == null || !kbId.equals(document.getKbId())) {
            return ApiResponse.fail("DOC_NOT_FOUND", "文档不存在", null);
        }
        return ApiResponse.ok(documentChunkService.listByDocId(docId), null);
    }

    @PostMapping("/{docId}/archive")
    public ApiResponse<Void> archiveDocument(
            @PathVariable String kbId,
            @PathVariable String docId) {
        try {
            documentProcessingService.archiveDocument(kbId, docId);
            return ApiResponse.ok(null, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("INVALID_STATE", e.getMessage(), null);
        } catch (Exception e) {
            log.error("归档文档失败: kbId={}, docId={}", kbId, docId, e);
            return ApiResponse.fail("ARCHIVE_FAILED", "归档文档失败: " + e.getMessage(), null);
        }
    }

    @PostMapping("/{docId}/activate")
    public ApiResponse<Void> activateDocument(
            @PathVariable String kbId,
            @PathVariable String docId) {
        try {
            documentProcessingService.activateDocument(kbId, docId);
            return ApiResponse.ok(null, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("INVALID_STATE", e.getMessage(), null);
        } catch (Exception e) {
            log.error("激活文档失败: kbId={}, docId={}", kbId, docId, e);
            return ApiResponse.fail("ACTIVATE_FAILED", "激活文档失败: " + e.getMessage(), null);
        }
    }

    @PostMapping("/{docId}/governance/review")
    public ApiResponse<DocumentInfoResponse> reviewDocument(
            @PathVariable String kbId,
            @PathVariable String docId,
            @RequestBody DocumentGovernanceUpdateRequest request) {
        try {
            KnowledgeDocument document = medicalDocumentGovernanceService.reviewDocument(kbId, docId, request);
            DocumentInfoResponse response = new DocumentInfoResponse();
            BeanUtils.copyProperties(document, response);
            return ApiResponse.ok(response, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("DOC_NOT_FOUND", e.getMessage(), null);
        } catch (Exception e) {
            log.error("审核文档失败: kbId={}, docId={}", kbId, docId, e);
            return ApiResponse.fail("DOC_REVIEW_FAILED", "审核文档失败: " + e.getMessage(), null);
        }
    }

    @PostMapping("/{docId}/governance/invalidate")
    public ApiResponse<DocumentInfoResponse> invalidateDocument(
            @PathVariable String kbId,
            @PathVariable String docId,
            @RequestBody DocumentGovernanceUpdateRequest request) {
        try {
            KnowledgeDocument document = medicalDocumentGovernanceService.invalidateDocument(kbId, docId, request);
            DocumentInfoResponse response = new DocumentInfoResponse();
            BeanUtils.copyProperties(document, response);
            return ApiResponse.ok(response, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("DOC_NOT_FOUND", e.getMessage(), null);
        } catch (Exception e) {
            log.error("失效文档失败: kbId={}, docId={}", kbId, docId, e);
            return ApiResponse.fail("DOC_INVALIDATE_FAILED", "失效文档失败: " + e.getMessage(), null);
        }
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{docId}")
    public ApiResponse<Void> deleteDocument(
            @PathVariable String kbId,
            @PathVariable String docId) {

        log.info("删除文档: kbId={}, docId={}", kbId, docId);

        KnowledgeDocument document = documentService.getByDocId(docId);
        if (document == null || !document.getKbId().equals(kbId)) {
            return ApiResponse.fail("DOC_NOT_FOUND", "文档不存在", null);
        }

        try {
            documentProcessingService.deleteDocument(kbId, docId);
            return ApiResponse.ok(null, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail("DOC_NOT_FOUND", e.getMessage(), null);
        } catch (Exception e) {
            log.error("删除文档失败: kbId={}, docId={}", kbId, docId, e);
            return ApiResponse.fail("DELETE_FAILED", "删除文档失败: " + e.getMessage(), null);
        }
    }
}
