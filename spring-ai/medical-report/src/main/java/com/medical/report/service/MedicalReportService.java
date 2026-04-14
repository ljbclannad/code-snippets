package com.medical.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medical.report.exception.MedicalReportException;
import com.medical.report.model.MedicalReportResponse;
import com.medical.report.model.QuestionnaireRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class MedicalReportService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final PdfParserService pdfParserService;
    private final String promptTemplate;

    public MedicalReportService(ChatClient chatClient,
                                PdfParserService pdfParserService,
                                @Value("classpath:prompts/medical-report-template.txt")
                                org.springframework.core.io.Resource promptResource) throws Exception {
        this.chatClient = chatClient;
        this.pdfParserService = pdfParserService;
        this.objectMapper = new ObjectMapper();
        this.promptTemplate = new String(promptResource.getInputStream().readAllBytes());
    }

    @Cacheable(value = "medicalReports", key = "#request.hashCode()")
    public MedicalReportResponse generateReport(QuestionnaireRequest request) {
        try {
            String prompt = buildPrompt(request);
            log.debug("Generated prompt: {}", prompt);

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            log.debug("AI response: {}", response);

            return parseResponse(response, request.getName());
        } catch (Exception e) {
            log.error("Failed to generate medical report for patient: {}", request.getName(), e);
            throw new MedicalReportException("生成医疗报告失败: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(QuestionnaireRequest request) {
        try {
            String questionnaireJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(request);

            String hasPdf = "";
            String pdfContent = "";

            if (request.getReportFile() != null &&
                Boolean.TRUE.equals(request.getReportFile().getIsUpload()) &&
                request.getReportFile().getFileList() != null &&
                !request.getReportFile().getFileList().isEmpty()) {

                String pdfText = extractPdfContent(request.getReportFile().getFileList().get(0));
                if (pdfText != null && !pdfText.isBlank()) {
                    hasPdf = "和检验报告";
                    pdfContent = "\n\n检验报告内容：\n" + pdfText;
                }
            }

            return promptTemplate
                    .replace("{hasPdf}", hasPdf)
                    .replace("{questionnaireData}", questionnaireJson)
                    .replace("{pdfContent}", pdfContent);
        } catch (JsonProcessingException e) {
            throw new MedicalReportException("问卷数据序列化失败", e);
        }
    }

    private String extractPdfContent(QuestionnaireRequest.FileInfo fileInfo) {
        try {
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

    private MedicalReportResponse parseResponse(String response, String patientName) {
        String jsonContent = extractJson(response);

        try {
            MedicalReportResponse report = objectMapper.readValue(jsonContent, MedicalReportResponse.class);
            report.setPatientName(patientName);
            report.setReportDate(LocalDate.now().format(DateTimeFormatter.ISO_DATE));
            return report;
        } catch (JsonProcessingException e) {
            throw new MedicalReportException("AI响应解析失败，返回内容格式不正确", e);
        }
    }

    private String extractJson(String response) {
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }
}
