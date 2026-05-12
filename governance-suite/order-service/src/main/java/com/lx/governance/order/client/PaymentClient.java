package com.lx.governance.order.client;

import feign.Headers;
import feign.RequestLine;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.order.config.FeignConfig;
import com.lx.governance.order.domain.PaymentPrepareRequest;

@FeignClient(
        name = "payment-service",
        configuration = FeignConfig.class,
        fallback = PaymentClient.PaymentClientFallback.class)
public interface PaymentClient {

    @RequestLine("POST /prepare")
    @Headers("Content-Type: application/json")
    ApiResponse<String> prepare(PaymentPrepareRequest req);

    @Component
    class PaymentClientFallback implements PaymentClient {
        @Override
        public ApiResponse<String> prepare(PaymentPrepareRequest req) {
            return ApiResponse.fail("PAYMENT_UNAVAILABLE", "payment-service fallback", null);
        }
    }
}
