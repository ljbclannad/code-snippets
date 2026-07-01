package com.lx.governance.vectormemory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "memory.lifecycle")
public class MemoryLifecycleProperties {

    private Retention retention = new Retention();
    private Decay decay = new Decay();
    private Cleanup cleanup = new Cleanup();
    private Compaction compaction = new Compaction();
    private Task task = new Task();

    @Data
    public static class Retention {
        private int symptomTtlDays = 30;
        private int preferenceInactivityDays = 180;
        private int factInactivityDays = 365;
        private int lowValueSourceThreshold = 1;
    }

    @Data
    public static class Decay {
        private double penaltyFactor = 0.25d;
        private double defaultDecayScore = 0.35d;
        private int scanLimit = 100;
    }

    @Data
    public static class Cleanup {
        private int archiveAfterDecayDays = 30;
        private int deleteAfterArchiveDays = 90;
    }

    @Data
    public static class Compaction {
        private int minEvidenceCount = 2;
        private int minSourceCount = 2;
        private int longCycleEventDays = 14;
        private int scanLimit = 100;
    }

    @Data
    public static class Task {
        private long fixedDelayMs = 300000L;
        private long initialDelayMs = 90000L;
    }
}
