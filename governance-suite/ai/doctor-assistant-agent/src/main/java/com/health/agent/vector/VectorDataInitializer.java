package com.health.agent.vector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 向量数据初始化器
 * 在应用启动时自动加载测试病例数据到 Milvus
 */
@Slf4j
@Component
public class VectorDataInitializer implements CommandLineRunner {

    @Autowired
    private VectorSearchService vectorSearchService;

    @Value("${milvus.enabled:false}")
    private boolean milvusEnabled;

    @Value("${vector.init-data:false}")
    private boolean initData;

    @Override
    public void run(String... args) {
        if (!milvusEnabled || !initData) {
            log.info("向量数据初始化已跳过: milvusEnabled={}, initData={}", milvusEnabled, initData);
            return;
        }

        log.info("开始初始化向量数据...");

        try {
            List<CaseVector> testCases = createTestCases();
            vectorSearchService.batchAddCases(testCases);
            log.info("向量数据初始化完成: count={}", testCases.size());
        } catch (Exception e) {
            log.error("向量数据初始化失败", e);
        }
    }

    /**
     * 创建测试病例数据
     */
    private List<CaseVector> createTestCases() {
        return Arrays.asList(
            createCase(
                "CASE_001", "C001",
                "35岁女性患者，有高血压病史，通过饮食控制和运动，30天内成功减重3kg，血压控制良好",
                "高血压控制稳定，体重管理成功，建议继续保持健康生活方式",
                Arrays.asList("高血压", "超重"),
                Arrays.asList("高血压"),
                Arrays.asList("厄贝沙坦片", "阿司匹林")
            ),
            createCase(
                "CASE_002", "C002",
                "42岁男性患者，2型糖尿病，通过药物治疗和饮食调整，血糖控制在正常范围，体重略有下降",
                "糖尿病控制良好，血糖稳定，建议定期监测",
                Arrays.asList("高血糖", "肥胖"),
                Arrays.asList("糖尿病", "高血脂"),
                Arrays.asList("二甲双胍", "阿托伐他汀")
            ),
            createCase(
                "CASE_003", "C001",
                "35岁女性患者，最近出现焦虑情绪和睡眠质量下降，通过心理咨询和放松训练有所改善",
                "轻度焦虑，建议继续心理咨询，保持规律作息",
                Arrays.asList("焦虑", "失眠"),
                Arrays.asList("高血压"),
                Arrays.asList("厄贝沙坦片")
            ),
            createCase(
                "CASE_004", "C003",
                "28岁女性患者，BMI正常但希望进一步减重，制定了科学的减重计划，包括饮食和运动",
                "健康状况良好，建议适度减重，避免过度节食",
                Arrays.asList(),
                Arrays.asList(),
                Arrays.asList()
            ),
            createCase(
                "CASE_005", "C004",
                "50岁男性患者，高血压和高血脂，通过药物治疗和生活方式调整，指标逐步改善",
                "高血压和高血脂控制中，需要长期服药和定期复查",
                Arrays.asList("高血压", "高血脂", "头晕"),
                Arrays.asList("高血压", "高血脂"),
                Arrays.asList("厄贝沙坦片", "阿托伐他汀", "阿司匹林")
            ),
            createCase(
                "CASE_006", "C005",
                "55岁女性患者，糖尿病10年，出现早期肾功能异常，调整用药方案，加强血糖监测",
                "糖尿病肾病早期，需要严格控制血糖，定期监测肾功能",
                Arrays.asList("高血糖", "蛋白尿"),
                Arrays.asList("糖尿病"),
                Arrays.asList("二甲双胍", "胰岛素")
            ),
            createCase(
                "CASE_007", "C006",
                "30岁男性患者，减重过程中运动过度导致膝关节损伤，调整运动方案，改为低强度运动",
                "膝关节劳损，建议休息和物理治疗，减重计划需要调整",
                Arrays.asList("膝关节疼痛", "超重"),
                Arrays.asList(),
                Arrays.asList("布洛芬")
            ),
            createCase(
                "CASE_008", "C007",
                "60岁男性患者，高血压多年，最近出现心律不齐，调整降压药，增加心脏监测",
                "高血压合并心律不齐，需要综合治疗，定期心电图检查",
                Arrays.asList("高血压", "心悸", "胸闷"),
                Arrays.asList("高血压"),
                Arrays.asList("厄贝沙坦片", "美托洛尔", "阿司匹林")
            ),
            createCase(
                "CASE_009", "C008",
                "58岁女性患者，糖尿病15年，出现糖尿病视网膜病变，需要眼科治疗和严格血糖控制",
                "糖尿病视网膜病变，需要激光治疗，严格控制血糖",
                Arrays.asList("视力下降", "高血糖"),
                Arrays.asList("糖尿病"),
                Arrays.asList("二甲双胍", "胰岛素")
            ),
            createCase(
                "CASE_010", "C009",
                "38岁女性患者，通过科学的饮食和运动计划，6个月成功减重15kg，各项指标恢复正常",
                "减重成功，健康状况显著改善，建议保持当前生活方式",
                Arrays.asList(),
                Arrays.asList(),
                Arrays.asList()
            )
        );
    }

    /**
     * 创建病例向量对象
     */
    private CaseVector createCase(
        String caseId,
        String customerId,
        String description,
        String diagnosis,
        List<String> symptoms,
        List<String> chronicDiseases,
        List<String> medications
    ) {
        CaseVector cv = new CaseVector();
        cv.setCaseId(caseId);
        cv.setCustomerId(customerId);
        cv.setDescription(description);
        cv.setDiagnosis(diagnosis);
        cv.setSymptoms(symptoms);
        cv.setChronicDiseases(chronicDiseases);
        cv.setMedications(medications);
        return cv;
    }
}
