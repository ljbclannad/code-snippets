package com.lx.governance.payment.service;

import java.time.Duration;
import java.util.Map;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

    public PaymentAppService(PaymentRepository repository, StringRedisTemplate redisTemplate,
            RabbitTemplate rabbitTemplate) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    @SentinelResource(value = "payment_prepare", blockHandler = "blockPrepare", fallback = "fallbackPrepare")
    public PaymentDto prepare(PreparePaymentRequest req) {
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
        repository.updateStatusByOrderNo(orderNo, "SUCCEEDED");
        PaymentDto p = repository.findByOrderNo(orderNo);
        // 发事件给 order-service
        Map<String, Object> event = Map.of(
                "orderNo", orderNo,
                "payNo", p != null ? p.getPayNo() : null,
                "amountCents", p != null ? p.getAmountCents() : null,
                "traceId", Trace.getOrCreate());
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.RK_PAYMENT_SUCCEEDED, Jsons.toJson(event));
        return p;
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
