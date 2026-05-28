package com.lx.governance.aichat.service;

import com.lx.governance.aichat.dto.DiagnosisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 追问响应构建器
 */
@Slf4j
@Service
public class FollowUpResponseBuilder {

    /**
     * 构建友好的追问响应
     *
     * @param questions 追问问题列表
     * @param diagnosis 诊断结果
     * @return 追问响应文本
     */
    public String buildFollowUpMessage(List<String> questions, DiagnosisResult diagnosis) {
        if (questions == null || questions.isEmpty()) {
            return "为了给您更准确的建议，我需要了解更多信息。";
        }

        StringBuilder message = new StringBuilder();

        // 1. 开场白（根据意图定制）
        message.append(getGreeting(diagnosis));
        message.append("\n\n");

        // 2. 追问问题
        if (questions.size() == 1) {
            message.append(questions.get(0));
        } else {
            message.append("为了给您更准确的建议，我需要了解以下信息：\n");
            for (int i = 0; i < questions.size(); i++) {
                message.append(i + 1).append(". ").append(questions.get(i)).append("\n");
            }
        }

        // 3. 引导语
        if (questions.size() > 1) {
            message.append("\n您可以一次回答所有问题，也可以逐个回答。");
        }

        return message.toString();
    }

    /**
     * 根据意图返回不同的开场白
     */
    private String getGreeting(DiagnosisResult diagnosis) {
        if (diagnosis == null || diagnosis.getIntent() == null) {
            return "我会尽力为您提供专业的健康建议！";
        }

        String intent = diagnosis.getIntent();

        if (intent.contains("血糖")) {
            return "我很乐意帮您管理血糖健康！";
        } else if (intent.contains("饮食")) {
            return "我会为您提供个性化的饮食建议！";
        } else if (intent.contains("运动")) {
            return "让我为您制定合适的运动计划！";
        } else if (intent.contains("血压")) {
            return "我会帮您更好地管理血压！";
        } else if (intent.contains("减肥")) {
            return "我会为您制定科学的减重方案！";
        } else if (intent.contains("用药")) {
            return "我会为您提供安全的用药建议！";
        } else if (intent.contains("睡眠")) {
            return "我会帮您改善睡眠质量！";
        }

        return "我会尽力为您提供专业的健康建议！";
    }
}
