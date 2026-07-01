package com.lx.governance.vectormemory.support.observability;

import java.util.Locale;

import org.springframework.stereotype.Service;

import com.lx.governance.vectormemory.dto.MemoryLifecycleDecision;
import com.lx.governance.vectormemory.enums.MemoryType;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryCandidateMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryConflictMapper;
import com.lx.governance.vectormemory.mapper.AiGroupMemoryMapper;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MemoryLifecycleMetricsService {

    private final MeterRegistry meterRegistry;

    public MemoryLifecycleMetricsService(MeterRegistry meterRegistry,
                                         AiGroupMemoryMapper memoryMapper,
                                         AiGroupMemoryCandidateMapper candidateMapper,
                                         AiGroupMemoryConflictMapper conflictMapper) {
        this.meterRegistry = meterRegistry;
        registerGauges(memoryMapper, candidateMapper, conflictMapper);
    }

    public void recordIngestionDecision(MemoryLifecycleDecision decision) {
        if (decision == null) {
            return;
        }
        String outcome = decision.getAction() == MemoryLifecycleDecision.LifecycleAction.INGEST_CANDIDATE ? "accepted" : "rejected";
        meterRegistry.counter("vector_memory.lifecycle.write.decisions.total",
                Tags.of("phase", "ingestion", "outcome", outcome)).increment();
    }

    public void recordConflict() {
        meterRegistry.counter("vector_memory.lifecycle.write.decisions.total",
                Tags.of("phase", "governance", "outcome", "conflict")).increment();
    }

    public void recordPromotion() {
        meterRegistry.counter("vector_memory.lifecycle.write.decisions.total",
                Tags.of("phase", "governance", "outcome", "promoted")).increment();
    }

    public void recordArchive() {
        meterRegistry.counter("vector_memory.lifecycle.write.decisions.total",
                Tags.of("phase", "retention", "outcome", "archived")).increment();
    }

    public void recordDelete() {
        meterRegistry.counter("vector_memory.lifecycle.write.decisions.total",
                Tags.of("phase", "retention", "outcome", "deleted")).increment();
    }

    public void recordCompaction() {
        meterRegistry.counter("vector_memory.lifecycle.write.decisions.total",
                Tags.of("phase", "compaction", "outcome", "summary_created")).increment();
    }

    public void recordJobRun(String jobName, boolean success) {
        meterRegistry.counter("vector_memory.lifecycle.jobs.total",
                Tags.of("job", jobName, "result", success ? "success" : "failure")).increment();
    }

    private void registerGauges(AiGroupMemoryMapper memoryMapper,
                                AiGroupMemoryCandidateMapper candidateMapper,
                                AiGroupMemoryConflictMapper conflictMapper) {
        Gauge.builder("vector_memory.lifecycle.candidate.oldest_hours",
                        candidateMapper, AiGroupMemoryCandidateMapper::selectOldestPendingAgeHours)
                .description("Oldest pending candidate lifetime in hours")
                .register(meterRegistry);

        Gauge.builder("vector_memory.lifecycle.candidate.stale.count",
                        candidateMapper, mapper -> mapper.countStaleCandidates(24))
                .description("Pending candidate count older than stale threshold")
                .register(meterRegistry);

        Gauge.builder("vector_memory.lifecycle.conflict.backlog.count",
                        conflictMapper, AiGroupMemoryConflictMapper::countOpenConflicts)
                .description("Open conflict review backlog")
                .register(meterRegistry);

        for (MemoryType memoryType : MemoryType.values()) {
            Gauge.builder("vector_memory.lifecycle.memory.stock.count",
                            memoryMapper, mapper -> mapper.countActiveByMemoryType(memoryType.name()))
                    .tags("memory_type", memoryType.name().toLowerCase(Locale.ROOT))
                    .description("Active memory stock distribution by memory type")
                    .register(meterRegistry);
        }
    }
}
