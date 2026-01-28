package com.example.order.web;

import com.example.common.Trace;
import org.springframework.stereotype.Component;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class TraceIdFilter extends org.springframework.web.filter.OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String traceId = request.getHeader(Trace.TRACE_ID);
            if (traceId != null && !traceId.isBlank()) {
                Trace.set(traceId);
            } else {
                Trace.getOrCreate();
            }
            response.setHeader(Trace.TRACE_ID, Trace.getOrCreate());
            filterChain.doFilter(request, response);
        } finally {
            Trace.clear();
        }
    }
}
