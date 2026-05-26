package com.lx.governance.common.monitoring.aspect;

import com.lx.governance.common.monitoring.annotation.Monitored;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 监控切面，自动记录带 @Monitored 注解的方法的执行时间和成功率
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class MonitoringAspect {

    private final MeterRegistry meterRegistry;

    @Around("@annotation(monitored)")
    public Object monitor(ProceedingJoinPoint joinPoint, Monitored monitored) throws Throwable {
        String metricName = monitored.value();
        String methodName = joinPoint.getSignature().getName();

        // 创建 Timer 记录执行时间
        Timer timer = Timer.builder(metricName + ".duration")
                .description(monitored.description())
                .tag("method", methodName)
                .register(meterRegistry);

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            Object result = joinPoint.proceed();
            sample.stop(timer);

            // 记录成功次数
            Counter.builder(metricName + ".total")
                    .tag("status", "success")
                    .tag("method", methodName)
                    .register(meterRegistry)
                    .increment();

            return result;

        } catch (Throwable ex) {
            sample.stop(timer);

            // 记录失败次数
            if (monitored.recordFailures()) {
                Counter.builder(metricName + ".total")
                        .tag("status", "failure")
                        .tag("method", methodName)
                        .tag("exception", ex.getClass().getSimpleName())
                        .register(meterRegistry)
                        .increment();
            }

            throw ex;
        }
    }
}
