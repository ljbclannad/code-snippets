package com.medical.report.service;

import com.medical.report.exception.MedicalReportException;
import com.medical.report.manager.ContextWindowManager;
import com.medical.report.mapper.MedicalReportHistoryMapper;
import com.medical.report.model.MedicalReportResponseWithMetadata;
import com.medical.report.model.QuestionnaireRequest;
import com.medical.report.template.MedicalReportPromptTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicalReportServiceTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private PdfParserService pdfParserService;

    @Mock
    private MedicalReportHistoryMapper historyMapper;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callSpec;

    private MedicalReportService service;
    private MedicalReportPromptTemplate promptTemplate;
    private ContextWindowManager contextWindowManager;

    @BeforeEach
    void setUp() throws Exception {
        promptTemplate = new MedicalReportPromptTemplate(
                new ClassPathResource("prompts/medical-report-template.txt"));
        contextWindowManager = new ContextWindowManager();
        service = new MedicalReportService(chatClient, pdfParserService, historyMapper,
                promptTemplate, contextWindowManager);
    }

    @Test
    void generateReport_Success() {
        QuestionnaireRequest request = createTestRequest();
        String mockAiResponse = """
            {
              "healthAdvice": ["建议均衡饮食", "增加运动"],
              "riskAssessment": ["代谢风险中等"],
              "medicalGuidance": ["建议3个月内就诊内分泌科"]
            }
            """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(mockAiResponse);

        MedicalReportResponseWithMetadata response = service.generateReport(request);

        assertNotNull(response);
        assertNotNull(response.getReport());
        assertEquals("张三", response.getReport().getPatientName());
        assertNotNull(response.getReport().getReportDate());
        assertEquals(2, response.getReport().getHealthAdvice().size());
        assertEquals(1, response.getReport().getRiskAssessment().size());
        assertEquals(1, response.getReport().getMedicalGuidance().size());

        verify(chatClient, times(1)).prompt();
    }

    @Test
    void generateReport_WithMarkdownCodeBlock() {
        QuestionnaireRequest request = createTestRequest();
        String mockAiResponse = """
            ```json
            {
              "healthAdvice": ["建议均衡饮食"],
              "riskAssessment": ["风险较低"],
              "medicalGuidance": ["定期体检"]
            }
            ```
            """;

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(mockAiResponse);

        MedicalReportResponseWithMetadata response = service.generateReport(request);

        assertNotNull(response);
        assertEquals(1, response.getReport().getHealthAdvice().size());
    }

    @Test
    void generateReport_InvalidJsonResponse() {
        QuestionnaireRequest request = createTestRequest();
        String invalidResponse = "This is not a valid JSON response";

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(invalidResponse);

        assertThrows(MedicalReportException.class, () -> service.generateReport(request));
    }

    @Test
    void generateReport_ChatClientThrowsException() {
        QuestionnaireRequest request = createTestRequest();

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("API call failed"));

        assertThrows(MedicalReportException.class, () -> service.generateReport(request));
    }

    @Test
    void generateReport_WithPdfFile() {
        QuestionnaireRequest request = createTestRequestWithPdf();
        String mockAiResponse = """
            {
              "healthAdvice": ["根据检验报告建议"],
              "riskAssessment": ["需关注指标异常"],
              "medicalGuidance": ["建议复查"]
            }
            """;

        when(pdfParserService.extractTextFromUrl(any())).thenReturn("检验报告内容");
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(mockAiResponse);

        MedicalReportResponseWithMetadata response = service.generateReport(request);

        assertNotNull(response);
        verify(pdfParserService, times(1)).extractTextFromUrl(any());
    }

    private QuestionnaireRequest createTestRequest() {
        QuestionnaireRequest request = new QuestionnaireRequest();
        request.setName("张三");
        request.setAge("30");
        request.setGender("男");
        request.setWeight("70");
        request.setHeight("175");
        request.setBmi("22.86");
        request.setFamilyDiseases(List.of("高血压"));
        request.setConditions(List.of());
        request.setPreferDiet("清淡");
        request.setSportHabits("每周3次");
        request.setHealthGoals(List.of("减重", "改善睡眠"));
        return request;
    }

    private QuestionnaireRequest createTestRequestWithPdf() {
        QuestionnaireRequest request = createTestRequest();

        QuestionnaireRequest.ReportFile reportFile = new QuestionnaireRequest.ReportFile();
        reportFile.setIsUpload(true);

        QuestionnaireRequest.FileInfo fileInfo = new QuestionnaireRequest.FileInfo();
        fileInfo.setName("test.pdf");
        fileInfo.setUrl("https://example.com/test.pdf");
        fileInfo.setFileType("pdf");

        reportFile.setFileList(List.of(fileInfo));
        request.setReportFile(reportFile);

        return request;
    }
}
