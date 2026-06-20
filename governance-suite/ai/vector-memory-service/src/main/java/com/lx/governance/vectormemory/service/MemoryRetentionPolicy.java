package com.lx.governance.vectormemory.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.config.MemoryLifecycleProperties;
import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;

import lombok.RequiredArgsConstructor;
import lombok.Value;

@Service
@RequiredArgsConstructor
public class MemoryRetentionPolicy {

    private final MemoryLifecycleProperties properties;

    public RetentionDecision evaluate(AiGroupMemoryEntity memory, LocalDateTime now) {
        String memoryType = memory.getMemoryType();
        String lifecycleStatus = memory.getLifecycleStatus() == null ? "CONFIRMED" : memory.getLifecycleStatus();
        LocalDateTime referenceTime = firstNonNull(
                memory.getLastAccessedAt(),
                memory.getLastReinforcedAt(),
                memory.getExtractedAt(),
                memory.getCreateTime(),
                now);

        if ("ARCHIVED".equalsIgnoreCase(lifecycleStatus)) {
            LocalDateTime archivedAt = firstNonNull(memory.getArchivedAt(), referenceTime);
            if (archivedAt.plusDays(properties.getCleanup().getDeleteAfterArchiveDays()).isBefore(now)
                    || archivedAt.plusDays(properties.getCleanup().getDeleteAfterArchiveDays()).isEqual(now)) {
                return new RetentionDecision("DELETE", "archive_retention_expired",
                        "archived memory exceeded delete retention window");
            }
            return new RetentionDecision("KEEP", "archived_keep", "archived memory still within audit retention window");
        }

        if (isExpired(memory, now)) {
            if ("DECAYING".equalsIgnoreCase(lifecycleStatus)) {
                LocalDateTime decayingAt = firstNonNull(memory.getUpdateTime(), referenceTime);
                if (decayingAt.plusDays(properties.getCleanup().getArchiveAfterDecayDays()).isBefore(now)
                        || decayingAt.plusDays(properties.getCleanup().getArchiveAfterDecayDays()).isEqual(now)) {
                    return new RetentionDecision("ARCHIVE", "expired_after_decay", "expired memory should be archived");
                }
                return new RetentionDecision("DECAY", "expired_pending_archive", "expired memory remains decaying before archive");
            }
            return new RetentionDecision("DECAY", "ttl_expired", "memory reached retention ttl and should decay");
        }

        if (isLowValue(memory, referenceTime, now)) {
            if ("DECAYING".equalsIgnoreCase(lifecycleStatus)) {
                LocalDateTime decayingAt = firstNonNull(memory.getUpdateTime(), referenceTime);
                if (decayingAt.plusDays(properties.getCleanup().getArchiveAfterDecayDays()).isBefore(now)
                        || decayingAt.plusDays(properties.getCleanup().getArchiveAfterDecayDays()).isEqual(now)) {
                    return new RetentionDecision("ARCHIVE", "low_value_archived", "low-value memory should be archived");
                }
                return new RetentionDecision("DECAY", "low_value_decaying", "low-value memory remains decaying");
            }
            return new RetentionDecision("DECAY", "low_value_memory", "low-value memory should reduce retrieval weight");
        }

        return new RetentionDecision("KEEP", "retention_keep", "memory remains active");
    }

    public void applyDefaults(AiGroupMemoryEntity memory) {
        if (memory.getRetentionPolicy() == null || memory.getRetentionPolicy().isBlank()) {
            memory.setRetentionPolicy(defaultPolicy(memory.getMemoryType()));
        }
        if (memory.getLifecycleStatus() == null || memory.getLifecycleStatus().isBlank()) {
            memory.setLifecycleStatus("CONFIRMED");
        }
        if (memory.getExpiresAt() == null && "TIME_WINDOW".equalsIgnoreCase(memory.getRetentionPolicy())) {
            LocalDateTime baseTime = firstNonNull(memory.getExtractedAt(), memory.getLastReinforcedAt(), LocalDateTime.now());
            memory.setExpiresAt(baseTime.plusDays(properties.getRetention().getSymptomTtlDays()));
        }
    }

    public String defaultPolicy(String memoryType) {
        if ("SYMPTOM".equalsIgnoreCase(memoryType)) {
            return "TIME_WINDOW";
        }
        if ("PREFERENCE".equalsIgnoreCase(memoryType) || "DIET".equalsIgnoreCase(memoryType)) {
            return "LATEST_WINS";
        }
        return "LONG_TERM";
    }

    private boolean isExpired(AiGroupMemoryEntity memory, LocalDateTime now) {
        return memory.getExpiresAt() != null
                && (memory.getExpiresAt().isBefore(now) || memory.getExpiresAt().isEqual(now));
    }

    private boolean isLowValue(AiGroupMemoryEntity memory, LocalDateTime referenceTime, LocalDateTime now) {
        int sourceCount = memory.getSourceCount() == null ? 1 : memory.getSourceCount();
        String retentionPolicy = memory.getRetentionPolicy() == null ? defaultPolicy(memory.getMemoryType()) : memory.getRetentionPolicy();

        if ("LATEST_WINS".equalsIgnoreCase(retentionPolicy)) {
            return referenceTime.plusDays(properties.getRetention().getPreferenceInactivityDays()).isBefore(now)
                    || referenceTime.plusDays(properties.getRetention().getPreferenceInactivityDays()).isEqual(now);
        }

        if (sourceCount <= properties.getRetention().getLowValueSourceThreshold()) {
            return referenceTime.plusDays(properties.getRetention().getFactInactivityDays()).isBefore(now)
                    || referenceTime.plusDays(properties.getRetention().getFactInactivityDays()).isEqual(now);
        }

        return false;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Value
    public static class RetentionDecision {
        String action;
        String reasonCode;
        String summary;
    }
}
