package com.medical.report.template;

import com.medical.report.model.QuestionnaireRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Component
public class MedicalReportPromptTemplate {

    private final String baseTemplate;

    public MedicalReportPromptTemplate(
            @Value("classpath:prompts/medical-report-template.txt") Resource templateResource
    ) throws IOException {
        this.baseTemplate = templateResource.getContentAsString(StandardCharsets.UTF_8);
    }

    public String createPrompt(QuestionnaireRequest request, String pdfContent) {
        String hasPdf = (pdfContent != null && !pdfContent.isBlank()) ? "和检验报告" : "";
        String pdfSection = (pdfContent != null && !pdfContent.isBlank())
                ? "\n\n检验报告内容：\n" + pdfContent
                : "";

        PromptTemplate template = new PromptTemplate(baseTemplate);

        Map<String, Object> params = Map.of(
                "hasPdf", hasPdf,
                "questionnaireData", formatQuestionnaireData(request),
                "pdfContent", pdfSection
        );

        return template.render(params);
    }

    private String formatQuestionnaireData(QuestionnaireRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("患者基本信息：\n");
        sb.append("- 姓名：").append(request.getName()).append("\n");
        sb.append("- 年龄：").append(request.getAge()).append("岁\n");
        sb.append("- 性别：").append(request.getGender()).append("\n");
        sb.append("- 身高：").append(request.getHeight()).append("cm\n");
        sb.append("- 体重：").append(request.getWeight()).append("kg\n");
        sb.append("- BMI：").append(request.getBmi()).append("\n\n");

        if (request.getFamilyDiseases() != null && !request.getFamilyDiseases().isEmpty()) {
            sb.append("家族病史：").append(String.join("、", request.getFamilyDiseases())).append("\n");
        }

        if (request.getConditions() != null && !request.getConditions().isEmpty()) {
            sb.append("现有疾病：").append(String.join("、", request.getConditions())).append("\n");
        }

        if (request.getPreferDiet() != null) {
            sb.append("饮食习惯：").append(request.getPreferDiet()).append("\n");
        }

        if (request.getSportHabits() != null) {
            sb.append("运动习惯：").append(request.getSportHabits()).append("\n");
        }

        if (request.getHealthGoals() != null && !request.getHealthGoals().isEmpty()) {
            sb.append("健康目标：").append(String.join("、", request.getHealthGoals())).append("\n");
        }

        return sb.toString();
    }
}
