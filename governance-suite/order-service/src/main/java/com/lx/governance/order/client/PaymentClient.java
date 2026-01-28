package com.lx.governance.order.client;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.order.domain.PaymentPrepareRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "payment-service", path = "/", fallback = PaymentClient.PaymentClientFallback.class)
public interface PaymentClient {

    @PostMapping("/prepare")
    ApiResponse<String> prepare(@RequestBody PaymentPrepareRequest req);

    @Component
    class PaymentClientFallback implements PaymentClient {
        @Override
        public ApiResponse<String> prepare(PaymentPrepareRequest req) {
            return ApiResponse.fail("PAYMENT_UNAVAILABLE", "payment-service fallback", null);
        }
    }
}
