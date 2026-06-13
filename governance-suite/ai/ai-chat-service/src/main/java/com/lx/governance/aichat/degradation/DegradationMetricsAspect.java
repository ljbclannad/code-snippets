package com.lx.governance.aichat.degradation;

import com.lx.governance.aichat.metrics.AiChatMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 降级指标采集切面
 * 自动采集请求的并发、错误率、响应时间
 * 集成现有的 AiChatMetrics
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class DegradationMetricsAspect {

    private final SystemMetricsCollector metricsCollector;
    private final AiChatMetrics aiChatMetrics;

    /**
     * 拦截聊天服务方法，采集指标
     */
    @Around("execution(* com.lx.governance.aichat.service.AiChatService.handleChatRequest(..))")
    public Object collectMetrics(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();

        // 增加并发计数
        metricsCollector.incrementConcurrent();

        try {
            Object result = joinPoint.proceed();

            // 记录成功请求
            metricsCollector.recordRequest();

            return result;

        } catch (Throwable e) {
            // 记录错误
            metricsCollector.recordError();
            aiChatMetrics.recordChatError();
            throw e;

        } finally {
            // 减少并发计数
            metricsCollector.decrementConcurrent();

            // 记录响应时间
            long responseTime = System.currentTimeMillis() - startTime;
            metricsCollector.recordResponseTime(responseTime);
        }
    }

    /**
     * 拦截流式聊天服务方法，采集指标
     */
    @Around("execution(* com.lx.governance.aichat.service.AiChatStreamService.handleChatStreamRequest(..))")
    public Object collectStreamMetrics(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();

        // 增加并发计数
        metricsCollector.incrementConcurrent();

        try {
            Object result = joinPoint.proceed();

            // 记录成功请求
            metricsCollector.recordRequest();

            return result;

        } catch (Throwable e) {
            // 记录错误
            metricsCollector.recordError();
            aiChatMetrics.recordChatError();
            throw e;

        } finally {
            // 减少并发计数
            metricsCollector.decrementConcurrent();

            // 记录响应时间
            long responseTime = System.currentTimeMillis() - startTime;
            metricsCollector.recordResponseTime(responseTime);
        }
    }
}
