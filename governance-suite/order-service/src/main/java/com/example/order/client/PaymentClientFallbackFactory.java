package com.example.order.client;

import com.example.order.dto.PaymentPrepareRequest;
import com.example.order.dto.PaymentPrepareResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class PaymentClientFallbackFactory implements FallbackFactory<PaymentClient> {
    private static final Logger log = LoggerFactory.getLogger(PaymentClientFallbackFactory.class);

    @Override
    public PaymentClient create(Throwable cause) {
        return new PaymentClient() {
            @Override
            public PaymentPrepareResponse prepare(PaymentPrepareRequest req) {
                log.warn("PaymentClient fallback triggered: {}", cause.toString());
                PaymentPrepareResponse r = new PaymentPrepareResponse();
                r.setSuccess(false);
                r.setCode("PAYMENT_DOWN");
                r.setMessage("payment-service unavailable (fallback)");
                return r;
            }
        };
    }
}
