package com.lx.governance.common.filter;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Trace ID 过滤器
 * Micrometer Tracing 已自动从 traceparent header 提取 trace context
 * 这里只需要将 trace ID 放入 MDC 和响应头
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class TraceIdFilter extends OncePerRequestFilter {

    private final Tracer tracer;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        try {
            // Micrometer Tracing 已自动从 traceparent header 提取 trace context
            // 这里只需要将 trace ID 放入 MDC 和响应头
            TraceContext context = tracer.currentTraceContext().context();
            if (context != null) {
                String traceId = context.traceId();
                String spanId = context.spanId();

                MDC.put("traceId", traceId);
                MDC.put("spanId", spanId);
                response.setHeader("X-Trace-Id", traceId);

                log.debug("Request trace ID: {}, span ID: {}", traceId, spanId);
            }

            filterChain.doFilter(request, response);

        } finally {
            MDC.clear();
        }
    }
}
