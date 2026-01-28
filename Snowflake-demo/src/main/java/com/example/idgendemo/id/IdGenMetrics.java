package com.example.idgendemo.id;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class IdGenMetrics {

    private final MeterRegistry registry;

    private final ConcurrentHashMap<String, Counter> requestCounters = new ConcurrentHashMap<>();

    public final Counter clockBackwards;
    public final Counter sequenceOverflow;
    public final Counter workerLeaseLost;
    public final Counter segmentAllocate;
    public final Timer segmentAllocateTimer;

    public IdGenMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.clockBackwards = Counter.builder("idgen.clock_backwards")
                .description("Clock moved backwards count")
                .register(registry);
        this.sequenceOverflow = Counter.builder("idgen.sequence_overflow")
                .description("Snowflake sequence overflow count")
                .register(registry);
        this.workerLeaseLost = Counter.builder("idgen.worker_lease_lost")
                .description("Redis workerId lease lost count")
                .register(registry);
        this.segmentAllocate = Counter.builder("idgen.segment_allocate")
                .description("Segment allocation count")
                .register(registry);

        this.segmentAllocateTimer = Timer.builder("idgen.segment_allocate_time")
                .description("Segment allocation time")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void incRequest(String strategy) {
        Counter c = requestCounters.computeIfAbsent(strategy, s ->
                Counter.builder("idgen.requests")
                        .description("ID generation requests")
                        .tag("strategy", s)
                        .register(registry));
        c.increment();
    }

    public void recordSegmentAllocateNanos(long nanos) {
        segmentAllocateTimer.record(nanos, TimeUnit.NANOSECONDS);
    }
}
