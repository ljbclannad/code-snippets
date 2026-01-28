package com.lx.governance.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

public final class Trace {
    public static final String TRACE_ID = "traceId";

    private Trace() {}

    public static String getOrCreate() {
        String id = MDC.get(TRACE_ID);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString().replace("-", "");
            MDC.put(TRACE_ID, id);
        }
        return id;
    }

    public static void set(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TRACE_ID, traceId);
        }
    }

    public static void clear() {
        MDC.remove(TRACE_ID);
    }
}
