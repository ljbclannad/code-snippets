package com.lx.governance.common.trace;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Trace 工具类，从 Micrometer Tracing 获取 trace ID
 * 兼容现有代码，但改为使用 OpenTelemetry trace ID
 */
@Slf4j
@Component
public class Trace {

    public static final String TRACE_ID = "traceId";

    private static Tracer tracer;

    @Autowired
    public void setTracer(Tracer tracer) {
        Trace.tracer = tracer;
    }

    /**
     * 获取当前 trace ID（从 Micrometer Tracing）
     * 如果没有 trace context，生成临时 ID
     */
    public static String getTraceId() {
        if (tracer != null) {
            TraceContext context = tracer.currentTraceContext().context();
            if (context != null) {
                String traceId = context.traceId();
                String spanId = context.spanId();

                // 设置到 MDC 供日志使用
                MDC.put("traceId", traceId);
                MDC.put("spanId", spanId);

                return traceId;
            }
        }

        // Fallback：如果没有 trace context，生成临时 ID
        String fallbackId = generateFallbackId();
        MDC.put("traceId", fallbackId);
        return fallbackId;
    }

    /**
     * 获取或创建 trace ID（兼容旧 API）
     */
    public static String getOrCreate() {
        return getTraceId();
    }

    /**
     * 设置 trace ID（兼容旧 API，但优先使用 Micrometer Tracing 的 trace ID）
     */
    public static void set(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TRACE_ID, traceId);
        }
    }

    /**
     * 生成临时 trace ID（用于非 HTTP 请求场景）
     */
    private static String generateFallbackId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 清除 MDC 中的 trace 信息
     */
    public static void clear() {
        MDC.remove("traceId");
        MDC.remove("spanId");
    }
}
