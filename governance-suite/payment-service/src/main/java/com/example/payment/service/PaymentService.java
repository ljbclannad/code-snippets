package com.example.payment.service;

import com.example.payment.dto.PaymentPrepareRequest;
import com.example.payment.dto.PaymentPrepareResponse;
import com.example.payment.mapper.PaymentMapper;
import com.example.payment.model.PaymentEntity;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

@Service
public class PaymentService {
    private final PaymentMapper paymentMapper;
    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbitTemplate;

    public PaymentService(PaymentMapper paymentMapper, StringRedisTemplate redis, RabbitTemplate rabbitTemplate) {
        this.paymentMapper = paymentMapper;
        this.redis = redis;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public PaymentPrepareResponse prepare(PaymentPrepareRequest req) {
        // 幂等：同一个 orderNo 只创建一个 payment
        String idemKey = "pay:prepare:" + req.getOrderNo();
        Boolean ok = redis.opsForValue().setIfAbsent(idemKey, "1", Duration.ofMinutes(10));
        if (!Boolean.TRUE.equals(ok)) {
            PaymentEntity existed = paymentMapper.findByOrderNo(req.getOrderNo());
            PaymentPrepareResponse r = new PaymentPrepareResponse();
            r.setSuccess(true);
            r.setCode("OK");
            r.setMessage("idempotent hit");
            r.setPaymentNo(existed != null ? existed.getPaymentNo() : null);
            return r;
        }

        String paymentNo = "P" + UUID.randomUUID().toString().replace("-", "");
        PaymentEntity p = new PaymentEntity();
        p.setPaymentNo(paymentNo);
        p.setOrderNo(req.getOrderNo());
        p.setCustomerId(req.getCustomerId());
        p.setAmountCents(req.getAmountCents());
        p.setStatus("INIT");
        paymentMapper.insert(p);

        PaymentPrepareResponse r = new PaymentPrepareResponse();
        r.setSuccess(true);
        r.setCode("OK");
        r.setMessage("prepared");
        r.setPaymentNo(paymentNo);
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    public void confirmSuccess(String orderNo) {
        paymentMapper.updateStatus(orderNo, "SUCCESS");
        // 事件：PaymentSucceeded
        rabbitTemplate.convertAndSend("biz.events", "payment.succeeded",
                String.format("{\"orderNo\":\"%s\"}", orderNo));
    }
}
