package com.medical.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medical.report.model.MedicalReportResponse;
import com.medical.report.model.QuestionnaireRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class MedicalReportService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public MedicalReportService(ChatClient chatClient) {
        this.chatClient = chatClient;
        this.objectMapper = new ObjectMapper();
    }

    public MedicalReportResponse generateReport(QuestionnaireRequest request) {
        String prompt = buildPrompt(request);
        log.debug("Generated prompt: {}", prompt);

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        log.debug("AI response: {}", response);

        return parseResponse(response, request.getName());
    }

    private String buildPrompt(QuestionnaireRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请基于以下问卷数据生成一份医疗健康报告，以JSON格式返回，包含以下字段：\n");
        prompt.append("- healthAdvice: 健康建议（数组）\n");
        prompt.append("- riskAssessment: 风险评估（数组）\n");
        prompt.append("- medicalGuidance: 就医指导（数组）\n\n");
        prompt.append("问卷数据：\n");

        try {
            prompt.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize request", e);
            prompt.append(request.toString());
        }

        prompt.append("\n\n请直接返回JSON格式的结果，不要包含其他说明文字。");
        return prompt.toString();
    }

    private MedicalReportResponse parseResponse(String response, String patientName) {
        try {
            // 尝试提取JSON部分
            String jsonContent = extractJson(response);
            MedicalReportResponse report = objectMapper.readValue(jsonContent, MedicalReportResponse.class);

            // 设置患者姓名和报告日期
            report.setPatientName(patientName);
            report.setReportDate(LocalDate.now().format(DateTimeFormatter.ISO_DATE));

            return report;
        } catch (Exception e) {
            log.error("Failed to parse AI response as JSON, using fallback", e);
            return createFallbackResponse(response, patientName);
        }
    }

    private String extractJson(String response) {
        // 移除markdown代码块标记
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

    private MedicalReportResponse createFallbackResponse(String rawResponse, String patientName) {
        MedicalReportResponse response = new MedicalReportResponse();
        response.setPatientName(patientName);
        response.setReportDate(LocalDate.now().format(DateTimeFormatter.ISO_DATE));
        response.setHealthAdvice(java.util.List.of("AI响应解析失败，请查看原始响应"));
        response.setRiskAssessment(java.util.List.of("无法评估"));
        response.setMedicalGuidance(java.util.List.of("建议咨询专业医生"));
        return response;
    }
}
