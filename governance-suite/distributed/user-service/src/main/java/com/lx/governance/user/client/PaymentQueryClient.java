package com.lx.governance.user.client;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.user.domain.UserPaymentDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Collections;
import java.util.List;

@FeignClient(
        name = "payment-service",
        url = "${service.payment.base-url:}",
        path = "/",
        fallback = PaymentQueryClient.PaymentQueryClientFallback.class)
public interface PaymentQueryClient {

    @GetMapping("/user/{userId}/payments")
    ApiResponse<List<UserPaymentDto>> listByUserId(@PathVariable("userId") Long userId);

    @Component
    class PaymentQueryClientFallback implements PaymentQueryClient {
        @Override
        public ApiResponse<List<UserPaymentDto>> listByUserId(Long userId) {
            return ApiResponse.ok(Collections.emptyList(), null);
        }
    }
}
