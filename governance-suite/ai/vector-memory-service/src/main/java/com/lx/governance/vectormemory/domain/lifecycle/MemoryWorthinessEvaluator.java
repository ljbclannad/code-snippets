package com.lx.governance.vectormemory.domain.lifecycle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;

@Component
public class MemoryWorthinessEvaluator {

    private static final double MIN_CONFIDENCE = 0.70d;
    private static final double MIN_ACCEPTED_SCORE = 0.60d;

    public WorthinessResult evaluate(MemoryExtractionResult.ExtractedMemory extractedMemory, String normalizedMemoryType) {
        if (extractedMemory == null) {
            return WorthinessResult.reject("missing_extraction", 0.0d, "extracted memory is missing");
        }

        double confidence = extractedMemory.getConfidence() == null ? 0.0d : extractedMemory.getConfidence();
        if (confidence < MIN_CONFIDENCE) {
            return WorthinessResult.reject("low_confidence", confidence, "confidence is below lifecycle threshold");
        }

        String content = extractedMemory.getContent() == null ? "" : extractedMemory.getContent().trim();
        String normalizedContent = content.toLowerCase(Locale.ROOT);

        double score = confidence;

        if (content.length() >= 8) {
            score += 0.05d;
        }

        if ("PREFERENCE".equalsIgnoreCase(normalizedMemoryType) || "SYMPTOM".equalsIgnoreCase(normalizedMemoryType)) {
            score += 0.05d;
        }

        if (normalizedContent.contains("最近") || normalizedContent.contains("今天") || normalizedContent.contains("这次")) {
            score -= 0.10d;
        }

        if (normalizedContent.contains("可能") || normalizedContent.contains("也许")) {
            score -= 0.10d;
        }

        score = Math.max(0.0d, Math.min(1.0d, score));

        if (score < MIN_ACCEPTED_SCORE) {
            return WorthinessResult.reject("low_worthiness_score", score, "memory is not valuable enough for long-term storage");
        }

        return WorthinessResult.accept(score, "accepted_for_candidate", "memory is worthy for candidate ingestion");
    }

    public record WorthinessResult(boolean accepted, BigDecimal score, String reasonCode, String summary) {

        public static WorthinessResult accept(double score, String reasonCode, String summary) {
            return new WorthinessResult(true, toBigDecimal(score), reasonCode, summary);
        }

        public static WorthinessResult reject(String reasonCode, double score, String summary) {
            return new WorthinessResult(false, toBigDecimal(score), reasonCode, summary);
        }

        private static BigDecimal toBigDecimal(double value) {
            return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
        }
    }
}
