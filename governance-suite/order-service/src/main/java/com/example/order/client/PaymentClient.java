package com.example.order.client;

import com.example.order.dto.PaymentPrepareRequest;
import com.example.order.dto.PaymentPrepareResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "payment-service", fallbackFactory = PaymentClientFallbackFactory.class)
public interface PaymentClient {
    @PostMapping("/pay/prepare")
    PaymentPrepareResponse prepare(@RequestBody PaymentPrepareRequest req);
}
