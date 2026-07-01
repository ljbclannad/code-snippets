package com.lx.governance.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.order.domain.PaymentDto;
import com.lx.governance.order.domain.PaymentPrepareRequest;

@FeignClient(
        name = "payment-service",
        fallback = PaymentClient.PaymentClientFallback.class)
public interface PaymentClient {

    @PostMapping("/prepare")
    ApiResponse<PaymentDto> prepare(@RequestBody PaymentPrepareRequest req);

    @PostMapping("/tcc/try")
    ApiResponse<String> tryPrepareTcc(@RequestBody PaymentPrepareRequest req);

    @Component
    class PaymentClientFallback implements PaymentClient {
        @Override
        public ApiResponse<PaymentDto> prepare(PaymentPrepareRequest req) {
            return ApiResponse.fail("PAYMENT_UNAVAILABLE", "payment-service fallback", null);
        }

        @Override
        public ApiResponse<String> tryPrepareTcc(PaymentPrepareRequest req) {
            return ApiResponse.fail("PAYMENT_UNAVAILABLE", "payment-service fallback", null);
        }
    }
}
