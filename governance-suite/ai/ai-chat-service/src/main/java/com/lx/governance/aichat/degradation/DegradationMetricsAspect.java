package com.lx.governance.aichat.degradation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.StreamEvent;
import com.lx.governance.aichat.metrics.AiChatMetrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * 降级指标采集切面
 * 按请求真实完成时机采集并发、错误率、响应时间
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class DegradationMetricsAspect {

    private final SystemMetricsCollector metricsCollector;
    private final AiChatMetrics aiChatMetrics;

    @Around("execution(* com.lx.governance.aichat.service.AiChatService.handleChatRequest(..))")
    public Object collectMetrics(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        metricsCollector.incrementConcurrent();

        try {
            Object result = joinPoint.proceed();
            if (result instanceof CompletableFuture<?> future) {
                return future.whenComplete((response, ex) -> finishAsyncMetric(startTime, response, ex));
            }

            metricsCollector.recordRequest();
            metricsCollector.decrementConcurrent();
            metricsCollector.recordResponseTime(System.currentTimeMillis() - startTime);
            return result;
        } catch (Throwable e) {
            metricsCollector.recordError();
            aiChatMetrics.recordChatError();
            metricsCollector.decrementConcurrent();
            metricsCollector.recordResponseTime(System.currentTimeMillis() - startTime);
            throw e;
        } finally {
            if (Thread.currentThread().isInterrupted()) {
                log.debug("聊天请求指标采集线程被中断");
            }
        }
    }

    @Around("execution(* com.lx.governance.aichat.service.AiChatStreamService.handleChatStreamRequest(..))")
    public Object collectStreamMetrics(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            Object result = joinPoint.proceed();
            if (!(result instanceof Flux<?> flux)) {
                return result;
            }

            long startTime = System.currentTimeMillis();
            AtomicBoolean subscribed = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            AtomicBoolean hasErrorEvent = new AtomicBoolean(false);

            return flux.doOnSubscribe(subscription -> {
                        subscribed.set(true);
                        metricsCollector.incrementConcurrent();
                    })
                    .doOnNext(item -> {
                        if (item instanceof StreamEvent event) {
                            if (event.getType() == StreamEvent.EventType.ERROR) {
                                hasErrorEvent.set(true);
                            } else if (event.getType() == StreamEvent.EventType.DONE) {
                                completed.set(true);
                            }
                        }
                    })
                    .doOnError(ex -> {
                        hasErrorEvent.set(true);
                        aiChatMetrics.recordChatError();
                    })
                    .doFinally(signalType -> finishStreamMetric(
                            startTime, subscribed.get(), completed.get(), hasErrorEvent.get(), signalType));
        } catch (Throwable e) {
            metricsCollector.recordError();
            aiChatMetrics.recordChatError();
            throw e;
        }
    }

    private void finishAsyncMetric(long startTime, Object response, Throwable ex) {
        try {
            if (ex != null) {
                metricsCollector.recordError();
                aiChatMetrics.recordChatError();
                return;
            }

            if (response instanceof ChatResponse chatResponse && !Boolean.TRUE.equals(chatResponse.getSuccess())) {
                metricsCollector.recordError();
                aiChatMetrics.recordChatError();
                return;
            }

            metricsCollector.recordRequest();
        } finally {
            metricsCollector.decrementConcurrent();
            metricsCollector.recordResponseTime(System.currentTimeMillis() - startTime);
        }
    }

    private void finishStreamMetric(long startTime, boolean subscribed, boolean completed,
            boolean hasErrorEvent, SignalType signalType) {
        if (!subscribed) {
            return;
        }

        try {
            if (hasErrorEvent || signalType == SignalType.ON_ERROR || signalType == SignalType.CANCEL || !completed) {
                metricsCollector.recordError();
                aiChatMetrics.recordChatError();
            } else {
                metricsCollector.recordRequest();
            }
        } finally {
            metricsCollector.decrementConcurrent();
            metricsCollector.recordResponseTime(System.currentTimeMillis() - startTime);
        }
    }
}
