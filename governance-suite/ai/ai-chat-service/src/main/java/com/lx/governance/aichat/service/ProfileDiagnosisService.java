package com.lx.governance.aichat.service;

import com.lx.governance.aichat.dto.DiagnosisResult;
import com.lx.governance.aichat.dto.RequiredField;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.rule.ProfileCompletionRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 画像诊断服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileDiagnosisService {

    private final ProfileCompletionRule completionRule;
    private final FollowUpStateManager followUpStateManager;

    /**
     * 诊断画像完整性
     *
     * @param profile        用户画像
     * @param sessionContext 会话上下文
     * @param currentMessage 当前消息
     * @return 诊断结果
     */
    public DiagnosisResult diagnoseProfile(
            UserProfileDTO profile,
            SessionContext sessionContext,
            String currentMessage) {

        long startTime = System.currentTimeMillis();

        try {
            // 1. 获取用户意图
            String intent = sessionContext != null ? sessionContext.getUserIntent() : null;
            if (intent == null || intent.isEmpty()) {
                intent = "健康咨询"; // 默认意图
            }

            // 2. 根据意图匹配所需字段
            List<RequiredField> requiredFields = completionRule.matchRequiredFields(intent, currentMessage);

            if (requiredFields.isEmpty()) {
                log.debug("未匹配到必需字段规则: intent={}", intent);
                return DiagnosisResult.builder()
                        .needsCompletion(false)
                        .missingFields(new ArrayList<>())
                        .reason("未匹配到必需字段规则")
                        .confidence(1.0)
                        .intent(intent)
                        .build();
            }

            // 3. 检查哪些字段缺失
            List<DiagnosisResult.MissingField> missingFields = new ArrayList<>();
            String conversationId = sessionContext != null ? sessionContext.getConversationId() : null;

            for (RequiredField field : requiredFields) {
                // 跳过已追问过的字段
                if (conversationId != null && followUpStateManager.hasAskedField(conversationId, field.getPath())) {
                    log.debug("字段已追问过，跳过: {}", field.getPath());
                    continue;
                }

                // 检查字段是否缺失
                if (completionRule.isFieldMissing(profile, field.getPath())) {
                    missingFields.add(DiagnosisResult.MissingField.builder()
                            .fieldPath(field.getPath())
                            .fieldName(field.getName())
                            .priority(field.getPriority())
                            .suggestedQuestion(field.getQuestion())
                            .build());
                }
            }

            // 4. 按优先级排序（HIGH > MEDIUM > LOW）
            missingFields.sort(Comparator.comparing(DiagnosisResult.MissingField::getPriority));

            // 5. 判断是否需要补全
            boolean needsCompletion = !missingFields.isEmpty() &&
                    missingFields.stream().anyMatch(f -> f.getPriority() == DiagnosisResult.FieldPriority.HIGH);

            // 如果没有 HIGH 优先级字段缺失，但有 MEDIUM 优先级字段缺失，也可以追问
            if (!needsCompletion && missingFields.stream()
                    .anyMatch(f -> f.getPriority() == DiagnosisResult.FieldPriority.MEDIUM)) {
                needsCompletion = true;
            }

            long duration = System.currentTimeMillis() - startTime;

            DiagnosisResult result = DiagnosisResult.builder()
                    .needsCompletion(needsCompletion)
                    .missingFields(missingFields)
                    .reason(needsCompletion ? "关键画像字段缺失" : "画像完整")
                    .confidence(calculateConfidence(missingFields))
                    .intent(intent)
                    .build();

            log.info("画像诊断完成: intent={}, needsCompletion={}, missingFields={}, 耗时={}ms",
                    intent, needsCompletion, missingFields.size(), duration);

            return result;

        } catch (Exception e) {
            log.error("画像诊断失败", e);
            // 降级：返回不需要补全
            return DiagnosisResult.builder()
                    .needsCompletion(false)
                    .missingFields(new ArrayList<>())
                    .reason("诊断异常，降级处理")
                    .confidence(0.0)
                    .intent("未知")
                    .build();
        }
    }

    /**
     * 生成追问问题
     *
     * @param diagnosis    诊断结果
     * @param maxQuestions 最多问题数
     * @return 追问问题列表
     */
    public List<String> generateFollowUpQuestions(DiagnosisResult diagnosis, int maxQuestions) {
        if (diagnosis == null || diagnosis.getMissingFields() == null) {
            return new ArrayList<>();
        }

        // 按优先级取前 N 个缺失字段的追问问题
        return diagnosis.getMissingFields().stream()
                .limit(maxQuestions)
                .map(DiagnosisResult.MissingField::getSuggestedQuestion)
                .filter(q -> q != null && !q.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 计算诊断置信度
     * 基于缺失字段的数量和优先级
     */
    private double calculateConfidence(List<DiagnosisResult.MissingField> missingFields) {
        if (missingFields.isEmpty()) {
            return 1.0; // 完全充足
        }

        // 计算权重：HIGH=3, MEDIUM=2, LOW=1
        int totalWeight = 0;
        for (DiagnosisResult.MissingField field : missingFields) {
            switch (field.getPriority()) {
                case HIGH:
                    totalWeight += 3;
                    break;
                case MEDIUM:
                    totalWeight += 2;
                    break;
                case LOW:
                    totalWeight += 1;
                    break;
            }
        }

        // 假设最多缺失 5 个 HIGH 字段（权重 15）
        double maxWeight = 15.0;
        double confidence = 1.0 - (Math.min(totalWeight, maxWeight) / maxWeight);

        return Math.max(0.0, Math.min(1.0, confidence));
    }
}
