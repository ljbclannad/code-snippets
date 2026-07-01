package com.lx.governance.vectormemory.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lx.governance.vectormemory.config.MemoryLifecycleProperties;
import com.lx.governance.vectormemory.domain.lifecycle.MemoryCompactionService;
import com.lx.governance.vectormemory.domain.lifecycle.MemoryCompactionService.CompactionBatchResult;
import com.lx.governance.vectormemory.domain.lifecycle.MemoryDecayService;
import com.lx.governance.vectormemory.domain.lifecycle.MemoryDecayService.DecayBatchResult;
import com.lx.governance.vectormemory.support.observability.MemoryLifecycleMetricsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class MemoryLifecycleMaintenanceTask {

    private final MemoryDecayService memoryDecayService;
    private final MemoryCompactionService memoryCompactionService;
    private final MemoryLifecycleProperties properties;
    private final MemoryLifecycleMetricsService memoryLifecycleMetricsService;

    @Scheduled(fixedDelayString = "${memory.lifecycle.task.fixed-delay-ms:300000}",
            initialDelayString = "${memory.lifecycle.task.initial-delay-ms:90000}")
    public void maintainLifecycle() {
        try {
            DecayBatchResult result = memoryDecayService.processLifecycleMaintenance(
                    properties.getDecay().getScanLimit());
            CompactionBatchResult compactionResult = memoryCompactionService.compactCandidates(
                    properties.getCompaction().getScanLimit());
            memoryLifecycleMetricsService.recordJobRun("lifecycle_maintenance", true);
            log.info("memory lifecycle maintenance completed - decayed: {}, archived: {}, deleted: {}, unchanged: {}",
                    result.decayed(), result.archived(), result.deleted(), result.unchanged());
            log.info("memory compaction completed - created: {}, updated: {}, evidenceLinked: {}",
                    compactionResult.created(), compactionResult.updated(), compactionResult.evidenceLinked());
        } catch (Exception e) {
            memoryLifecycleMetricsService.recordJobRun("lifecycle_maintenance", false);
            throw e;
        }
    }
}
