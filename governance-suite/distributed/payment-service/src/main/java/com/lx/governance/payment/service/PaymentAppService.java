package com.lx.governance.payment.service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.lx.governance.common.id.IdGenerator;
import com.lx.governance.common.monitoring.annotation.Monitored;
import com.lx.governance.payment.domain.PaymentDto;
import com.lx.governance.payment.domain.PreparePaymentRequest;
import com.lx.governance.payment.repo.PaymentRepository;

@Service
public class PaymentAppService {

    private static final Logger log = LoggerFactory.getLogger(PaymentAppService.class);

    private final PaymentRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final PaymentFactEventPublisher paymentFactEventPublisher;
    private final boolean failPrepare;
    private final boolean failConfirm;
    private final IdGenerator payNoGenerator;

    public PaymentAppService(PaymentRepository repository,
            StringRedisTemplate redisTemplate,
            PaymentFactEventPublisher paymentFactEventPublisher,
            @Value("${lab.fail.prepare:false}") boolean failPrepare,
            @Value("${lab.fail.confirm:false}") boolean failConfirm,
            @Value("${id.mode:segment}") String mode,
            IdGenerator payNoSegmentGenerator,
            IdGenerator payNoSnowflakeGenerator) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.paymentFactEventPublisher = paymentFactEventPublisher;
        this.failPrepare = failPrepare;
        this.failConfirm = failConfirm;
        this.payNoGenerator = "snowflake".equalsIgnoreCase(mode) ? payNoSnowflakeGenerator : payNoSegmentGenerator;
    }

    @Monitored(value = "payment.prepare", description = "支付准备")
    @SentinelResource(value = "payment_prepare", blockHandler = "blockPrepare", fallback = "fallbackPrepare")
    @Transactional(rollbackFor = Exception.class)
    public PaymentDto prepare(PreparePaymentRequest req) {
        log.info("Payment preparation started",
                kv("event", "payment_prepare_start"),
                kv("orderId", req.getOrderNo()),
                kv("amount", req.getAmountCents()));

        try {
            if (failPrepare) {
                throw new IllegalStateException("lab injected failure: payment prepare");
            }
            if (req.getUserId() == null) {
                throw new IllegalArgumentException("userId is required for payment prepare");
            }

            String idemKey = "idem:pay:prepare:" + req.getUserId() + ":" + req.getOrderNo();
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(idemKey, "1", Duration.ofMinutes(10));
            if (!Boolean.TRUE.equals(ok)) {
                return repository.findByUserIdAndOrderNo(req.getUserId(), req.getOrderNo());
            }

            PaymentDto existing = repository.findByUserIdAndOrderNo(req.getUserId(), req.getOrderNo());
            if (existing != null) {
                return existing;
            }

            String payNo = "P" + payNoGenerator.nextId();
            try {
                repository.insert(req.getUserId(), payNo, req.getOrderNo(), req.getAmountCents(), "PREPARED", idemKey);
            } catch (DuplicateKeyException e) {
                return repository.findByUserIdAndOrderNo(req.getUserId(), req.getOrderNo());
            }
            PaymentDto result = repository.findByUserIdAndOrderNo(req.getUserId(), req.getOrderNo());
            paymentFactEventPublisher.publish(result);

            log.info("Payment prepared successfully",
                    kv("event", "payment_prepared"),
                    kv("paymentId", result.getPayNo()),
                    kv("orderId", req.getOrderNo()),
                    kv("amount", req.getAmountCents()),
                    kv("status", "PREPARED"));

            return result;
        } catch (Exception e) {
            log.error("Payment preparation failed",
                    kv("event", "payment_prepare_failed"),
                    kv("orderId", req.getOrderNo()),
                    kv("error", e.getMessage()),
                    kv("status", "FAILED"),
                    e);
            throw e;
        }
    }

    @Monitored(value = "payment.confirm", description = "支付确认")
    @Transactional(rollbackFor = Exception.class)
    public PaymentDto confirm(Long userId, String orderNo) {
        log.info("Payment confirmation started",
                kv("event", "payment_confirm_start"),
                kv("orderId", orderNo),
                kv("userId", userId));

        if (failConfirm) {
            throw new IllegalStateException("lab injected failure: payment confirm");
        }

        PaymentDto p = userId == null ? repository.findByOrderNo(orderNo)
                : repository.findByUserIdAndOrderNo(userId, orderNo);
        if (p == null) {
            throw new IllegalArgumentException("payment not found");
        }
        if ("SUCCEEDED".equalsIgnoreCase(p.getStatus())) {
            return p;
        }
        int updated;
        if (userId == null) {
            updated = repository.updateStatusByOrderNo(orderNo, "SUCCEEDED");
        } else {
            updated = repository.updateStatusByUserIdAndOrderNo(userId, orderNo, "SUCCEEDED");
        }
        p = userId == null ? repository.findByOrderNo(orderNo) : repository.findByUserIdAndOrderNo(userId, orderNo);
        if (updated > 0) {
            paymentFactEventPublisher.publish(p);
        }

        log.info("Payment confirmed successfully",
                kv("event", "payment_confirmed"),
                kv("paymentId", p.getPayNo()),
                kv("orderId", orderNo),
                kv("status", "CONFIRMED"));

        return p;
    }

    public java.util.List<PaymentDto> listByUserId(long userId) {
        return repository.findByUserId(userId);
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
