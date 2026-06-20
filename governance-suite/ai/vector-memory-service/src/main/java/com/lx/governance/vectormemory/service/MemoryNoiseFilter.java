package com.lx.governance.vectormemory.service;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.lx.governance.vectormemory.dto.MemoryExtractionResult;

@Component
public class MemoryNoiseFilter {

    private static final Set<String> NOISE_TEXTS = Set.of(
            "谢谢", "好的", "收到", "明白了", "好的谢谢", "ok", "okay", "thanks", "thank you");

    public NoiseFilterResult evaluate(MemoryExtractionResult.ExtractedMemory extractedMemory) {
        if (extractedMemory == null || extractedMemory.getContent() == null || extractedMemory.getContent().isBlank()) {
            return NoiseFilterResult.reject("empty_content", "memory content is empty");
        }

        String content = extractedMemory.getContent().trim();
        String normalized = content.toLowerCase(Locale.ROOT);

        if (content.length() < 4) {
            return NoiseFilterResult.reject("content_too_short", "memory content is too short");
        }

        if (NOISE_TEXTS.contains(content) || NOISE_TEXTS.contains(normalized)) {
            return NoiseFilterResult.reject("small_talk_noise", "small talk should not be stored as long-term memory");
        }

        if (normalized.startsWith("请帮我") || normalized.startsWith("帮我") || normalized.startsWith("现在")) {
            return NoiseFilterResult.reject("temporary_instruction", "temporary instruction should stay in session context");
        }

        return NoiseFilterResult.accept();
    }

    public record NoiseFilterResult(boolean accepted, String reasonCode, String summary) {

        public static NoiseFilterResult accept() {
            return new NoiseFilterResult(true, "accepted", "content passed noise filter");
        }

        public static NoiseFilterResult reject(String reasonCode, String summary) {
            return new NoiseFilterResult(false, reasonCode, summary);
        }
    }
}
