package com.lx.governance.common.web;

import com.lx.governance.common.trace.Trace;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId != null && !traceId.isBlank()) {
            Trace.set(traceId);
        }
        String current = Trace.getOrCreate();
        response.setHeader(HEADER, current);
        try {
            filterChain.doFilter(request, response);
        } finally {
            Trace.clear();
        }
    }
}
