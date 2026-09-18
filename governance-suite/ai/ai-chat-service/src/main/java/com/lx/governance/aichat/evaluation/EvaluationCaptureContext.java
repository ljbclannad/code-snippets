package com.lx.governance.aichat.evaluation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 每次调用独立持有，不借助 ThreadLocal 跨异步线程传递。 */
public final class EvaluationCaptureContext {
    final String captureId = UUID.randomUUID().toString();
    final long startedNanos = System.nanoTime();
    final AtomicBoolean completed = new AtomicBoolean();
    final EvaluationBinding binding;
    final ObjectNode input;
    final ObjectNode output;
    String runId;

    EvaluationCaptureContext(EvaluationBinding binding, ObjectNode input, ObjectNode output) {
        this.binding = binding;
        this.input = input;
        this.output = output;
    }
}
