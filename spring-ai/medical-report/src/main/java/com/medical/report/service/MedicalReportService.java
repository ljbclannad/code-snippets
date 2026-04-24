package com.medical.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medical.report.converter.MedicalReportSchema;
import com.medical.report.entity.MedicalReportHistory;
import com.medical.report.exception.MedicalReportException;
import com.medical.report.manager.ContextWindowManager;
import com.medical.report.model.MedicalReportResponse;
import com.medical.report.model.MedicalReportResponseWithMetadata;
import com.medical.report.model.QuestionnaireRequest;
import com.medical.report.model.TokenUsageInfo;
import com.medical.report.mapper.MedicalReportHistoryMapper;
import com.medical.report.template.MedicalReportPromptTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class MedicalReportService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final PdfParserService pdfParserService;
    private final MedicalReportHistoryMapper historyMapper;
    private final MedicalReportPromptTemplate promptTemplate;
    private final BeanOutputConverter<MedicalReportSchema> outputConverter;
    private final ContextWindowManager contextWindowManager;

    public MedicalReportService(ChatClient chatClient,
                                PdfParserService pdfParserService,
                                MedicalReportHistoryMapper historyMapper,
                                MedicalReportPromptTemplate promptTemplate,
                                ContextWindowManager contextWindowManager) {
        this.chatClient = chatClient;
        this.pdfParserService = pdfParserService;
        this.historyMapper = historyMapper;
        this.promptTemplate = promptTemplate;
        this.contextWindowManager = contextWindowManager;
        this.objectMapper = new ObjectMapper();
        this.outputConverter = new BeanOutputConverter<>(MedicalReportSchema.class);
    }

    @Cacheable(value = "medicalReports", key = "#request.hashCode()")
    @Transactional
    @Retryable(
            retryFor = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public MedicalReportResponseWithMetadata generateReport(QuestionnaireRequest request) {
        long startTime = System.currentTimeMillis();
        try {
            String pdfContent = extractPdfContent(request.getReportFile());

            // Optimize PDF content to fit context window
            if (pdfContent != null) {
                int pdfTokens = contextWindowManager.estimateTokens(pdfContent);
                log.info("PDF content tokens: {}", pdfTokens);

                if (pdfTokens > 50000) {
                    pdfContent = contextWindowManager.optimizeContent(pdfContent, 50000);
                    log.info("PDF content optimized to fit context window");
                }
            }

            String prompt = promptTemplate.createPrompt(request, pdfContent);

            // Validate total prompt size
            int totalTokens = contextWindowManager.estimateTokens(prompt);
            log.info("Total prompt tokens: {}", totalTokens);

            if (!contextWindowManager.isWithinLimit(prompt)) {
                log.warn("Prompt exceeds safe limit, optimizing...");
                prompt = contextWindowManager.optimizeContent(prompt,
                        contextWindowManager.getSafeTokenLimit());
            }

            String fullPrompt = prompt + "\n\n请严格按照以下JSON格式返回结果：\n" + outputConverter.getFormat();

            log.debug("Generated prompt with {} estimated tokens",
                    contextWindowManager.estimateTokens(fullPrompt));

            ChatResponse chatResponse = chatClient.prompt()
                    .user(fullPrompt)
                    .call()
                    .chatResponse();

            String responseContent = chatResponse.getResult().getOutput().getText();
            log.debug("AI response received");

            MedicalReportSchema schema = outputConverter.convert(responseContent);
            MedicalReportResponse report = schema.toResponse(
                    request.getName(),
                    LocalDate.now().format(DateTimeFormatter.ISO_DATE)
            );

            long responseTime = System.currentTimeMillis() - startTime;
            TokenUsageInfo tokenUsage = TokenUsageInfo.fromMetadata(
                    chatResponse.getMetadata(),
                    responseTime
            );

            log.info("Report generated - Tokens: {}/{}, Cost: ${}, Time: {}ms",
                    tokenUsage.getInputTokens(),
                    tokenUsage.getOutputTokens(),
                    String.format("%.6f", tokenUsage.getEstimatedCost()),
                    tokenUsage.getResponseTimeMs());

            saveToHistory(request, report);

            return MedicalReportResponseWithMetadata.builder()
                    .report(report)
                    .tokenUsage(tokenUsage)
                    .build();
        } catch (Exception e) {
            log.error("Failed to generate medical report for patient: {}", request.getName(), e);
            throw new MedicalReportException("生成医疗报告失败: " + e.getMessage(), e);
        }
    }

    public void generateReportStream(QuestionnaireRequest request,
                                     java.util.function.Consumer<String> onChunk,
                                     java.util.function.Consumer<MedicalReportResponse> onComplete) {
        try {
            String pdfContent = extractPdfContent(request.getReportFile());

            // Optimize PDF content for streaming
            if (pdfContent != null) {
                int pdfTokens = contextWindowManager.estimateTokens(pdfContent);
                if (pdfTokens > 50000) {
                    pdfContent = contextWindowManager.optimizeContent(pdfContent, 50000);
                    log.info("PDF content optimized for streaming: {} tokens", pdfTokens);
                }
            }

            String prompt = promptTemplate.createPrompt(request, pdfContent);

            // Validate and optimize prompt
            if (!contextWindowManager.isWithinLimit(prompt)) {
                prompt = contextWindowManager.optimizeContent(prompt,
                        contextWindowManager.getSafeTokenLimit());
                log.info("Prompt optimized for streaming");
            }

            String fullPrompt = prompt + "\n\n请严格按照以下JSON格式返回结果：\n" + outputConverter.getFormat();

            log.debug("Starting stream with {} estimated tokens",
                    contextWindowManager.estimateTokens(fullPrompt));

            StringBuilder fullResponse = new StringBuilder();

            chatClient.prompt()
                    .user(fullPrompt)
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
                        fullResponse.append(chunk);
                        onChunk.accept(chunk);
                    })
                    .doOnComplete(() -> {
                        try {
                            MedicalReportSchema schema = outputConverter.convert(fullResponse.toString());
                            MedicalReportResponse report = schema.toResponse(
                                    request.getName(),
                                    LocalDate.now().format(DateTimeFormatter.ISO_DATE)
                            );
                            saveToHistory(request, report);
                            onComplete.accept(report);
                            log.info("Streaming report generated and saved for patient: {}", request.getName());
                        } catch (Exception e) {
                            log.error("Failed to parse streaming response", e);
                        }
                    })
                    .doOnError(error -> {
                        log.error("Streaming error for patient: {}", request.getName(), error);
                    })
                    .subscribe();

        } catch (Exception e) {
            log.error("Failed to start streaming for patient: {}", request.getName(), e);
            throw new MedicalReportException("启动流式生成失败: " + e.getMessage(), e);
        }
    }

    private void saveToHistory(QuestionnaireRequest request, MedicalReportResponse response) {
        try {
            MedicalReportHistory history = new MedicalReportHistory();
            history.setPatientName(response.getPatientName());
            history.setAge(request.getAge());
            history.setGender(request.getGender());
            history.setQuestionnaireData(objectMapper.writeValueAsString(request));
            history.setHealthAdvice(String.join("\n", response.getHealthAdvice()));
            history.setRiskAssessment(String.join("\n", response.getRiskAssessment()));
            history.setMedicalGuidance(String.join("\n", response.getMedicalGuidance()));
            history.setReportDate(response.getReportDate());

            if (request.getReportFile() != null &&
                Boolean.TRUE.equals(request.getReportFile().getIsUpload()) &&
                request.getReportFile().getFileList() != null &&
                !request.getReportFile().getFileList().isEmpty()) {
                history.setPdfUrl(request.getReportFile().getFileList().get(0).getUrl());
            }

            history.setCreatedAt(java.time.LocalDateTime.now());
            historyMapper.insert(history);
            log.info("Saved report history for patient: {}, id: {}", response.getPatientName(), history.getId());
        } catch (Exception e) {
            log.error("Failed to save report history", e);
        }
    }

    private String extractPdfContent(QuestionnaireRequest.ReportFile reportFile) {
        try {
            if (reportFile == null || !Boolean.TRUE.equals(reportFile.getIsUpload()) ||
                reportFile.getFileList() == null || reportFile.getFileList().isEmpty()) {
                return null;
            }

            QuestionnaireRequest.FileInfo fileInfo = reportFile.getFileList().get(0);
            if (fileInfo == null || fileInfo.getUrl() == null) {
                return null;
            }

            log.info("Extracting PDF content from: {}", fileInfo.getName());
            return pdfParserService.extractTextFromUrl(fileInfo.getUrl());
        } catch (Exception e) {
            log.warn("Failed to extract PDF content, continuing without it: {}", e.getMessage());
            return null;
        }
    }
}
