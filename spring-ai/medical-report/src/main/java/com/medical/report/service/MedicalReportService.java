package com.medical.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medical.report.converter.MedicalReportSchema;
import com.medical.report.entity.MedicalReportHistory;
import com.medical.report.exception.MedicalReportException;
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

    public MedicalReportService(ChatClient chatClient,
                                PdfParserService pdfParserService,
                                MedicalReportHistoryMapper historyMapper,
                                MedicalReportPromptTemplate promptTemplate) {
        this.chatClient = chatClient;
        this.pdfParserService = pdfParserService;
        this.historyMapper = historyMapper;
        this.promptTemplate = promptTemplate;
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
            String prompt = promptTemplate.createPrompt(request, pdfContent);

            // Add structured output format instruction
            String fullPrompt = prompt + "\n\n请严格按照以下JSON格式返回结果：\n" + outputConverter.getFormat();

            log.debug("Generated prompt: {}", fullPrompt);

            ChatResponse chatResponse = chatClient.prompt()
                    .user(fullPrompt)
                    .call()
                    .chatResponse();

            String responseContent = chatResponse.getResult().getOutput().getContent();
            log.debug("AI response: {}", responseContent);

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
            String prompt = promptTemplate.createPrompt(request, pdfContent);
            String fullPrompt = prompt + "\n\n请严格按照以下JSON格式返回结果：\n" + outputConverter.getFormat();

            log.debug("Generated prompt for streaming: {}", fullPrompt);

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
