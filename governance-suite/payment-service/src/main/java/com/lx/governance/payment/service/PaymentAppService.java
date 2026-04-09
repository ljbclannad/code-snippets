package com.lx.governance.payment.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.payment.config.RabbitConfig;
import com.lx.governance.payment.domain.PaymentDto;
import com.lx.governance.payment.domain.PreparePaymentRequest;
import com.lx.governance.payment.repo.PaymentRepository;

@Service
public class PaymentAppService {

    private final PaymentRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final boolean failPrepare;
    private final boolean failConfirm;

    public PaymentAppService(PaymentRepository repository,
            StringRedisTemplate redisTemplate,
            RabbitTemplate rabbitTemplate,
            @Value("${lab.fail.prepare:false}") boolean failPrepare,
            @Value("${lab.fail.confirm:false}") boolean failConfirm) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.failPrepare = failPrepare;
        this.failConfirm = failConfirm;
    }

    @SentinelResource(value = "payment_prepare", blockHandler = "blockPrepare", fallback = "fallbackPrepare")
    public PaymentDto prepare(PreparePaymentRequest req) {
        if (failPrepare) {
            throw new IllegalStateException("lab injected failure: payment prepare");
        }

        String idemKey = "idem:pay:" + req.getOrderNo();
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(idemKey, "1", Duration.ofMinutes(10));
        if (!Boolean.TRUE.equals(ok)) {
            // 已准备过
            return repository.findByOrderNo(req.getOrderNo());
        }

        String payNo = "P" + System.currentTimeMillis() + (int) (Math.random() * 1000);
        repository.insert(payNo, req.getOrderNo(), req.getAmountCents(), "PREPARED");
        return repository.findByOrderNo(req.getOrderNo());
    }

    public PaymentDto confirm(String orderNo) {
        if (failConfirm) {
            throw new IllegalStateException("lab injected failure: payment confirm");
        }

        repository.updateStatusByOrderNo(orderNo, "SUCCEEDED");
        PaymentDto p = repository.findByOrderNo(orderNo);
        // 复用 traceId，便于串联业务日志与消息发送确认日志。
        String traceId = Trace.getOrCreate();
        // 发事件给 order-service
        Map<String, Object> event = Map.of(
                "orderNo", orderNo,
                "payNo", p != null ? p.getPayNo() : null,
                "amountCents", p != null ? p.getAmountCents() : null,
                "traceId", traceId);

        // CorrelationData 用于将发送请求与 broker confirm 对应起来。
        CorrelationData correlationData = new CorrelationData(orderNo + ":" + traceId + ":" + System.nanoTime());
        rabbitTemplate.convertAndSend(
                RabbitConfig.EXCHANGE,
                RabbitConfig.RK_PAYMENT_SUCCEEDED,
                Jsons.toJson(event),
                correlationData);
        // 同步等待发布确认，避免消息未入 broker 仍被当作发送成功。
        waitPublisherConfirm(correlationData);
        return p;
    }

    private void waitPublisherConfirm(CorrelationData correlationData) {
        try {
            // 等待 broker ACK；超时/失败直接抛错交由上层处理。
            CorrelationData.Confirm confirm = correlationData.getFuture().get(3, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new IllegalStateException(
                        "publish payment.succeeded not acknowledged: "
                                + (confirm == null ? "confirm is null" : confirm.getReason()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("publish payment.succeeded interrupted", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("publish payment.succeeded confirm timeout", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("publish payment.succeeded confirm failed", e);
        }
    }

    // Sentinel block handler
    public PaymentDto blockPrepare(PreparePaymentRequest req, com.alibaba.csp.sentinel.slots.block.BlockException ex) {
        throw new IllegalArgumentException("payment_prepare blocked: " + ex.getClass().getSimpleName());
    }

    // fallback for business exceptions
    public PaymentDto fallbackPrepare(PreparePaymentRequest req, Throwable ex) {
        throw new IllegalArgumentException("payment_prepare fallback: " + ex.getMessage());
    }
}
