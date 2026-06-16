package com.lx.governance.vectormemory.service;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.lx.governance.vectormemory.enums.MemoryType;

/**
 * 统一 memoryType / category 的中英文归一化。
 *
 * 目标：
 * 1. 模型抽取分类（preference/health/...）统一映射到内部 memoryType
 * 2. 管理端或 DSL 传入的中文标签（偏好/健康/...）统一映射到内部 memoryType
 * 3. 内部已使用的标准值保持幂等
 */
@Component
public class MemoryTypeNormalizer {

    public String normalize(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return MemoryType.OTHER.name();
        }

        String normalized = rawType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "preference", "偏好", "喜好", "习惯", "风格" -> MemoryType.PREFERENCE.name();
            case "health", "健康", "症状", "身体状况", "病情", "symptom" -> MemoryType.SYMPTOM.name();
            case "allergy", "过敏" -> MemoryType.ALLERGY.name();
            case "medication", "用药", "药物" -> MemoryType.MEDICATION.name();
            case "diet", "饮食" -> MemoryType.DIET.name();
            case "goal", "目标", "plan", "计划", "fact", "事实", "context", "上下文", "other", "其他", "emotion", "情绪" ->
                    MemoryType.OTHER.name();
            default -> {
                try {
                    yield MemoryType.valueOf(rawType.trim().toUpperCase(Locale.ROOT)).name();
                } catch (IllegalArgumentException ignored) {
                    yield MemoryType.OTHER.name();
                }
            }
        };
    }
}
