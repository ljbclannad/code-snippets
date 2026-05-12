package com.lx.governance.order.client;

import feign.Headers;
import feign.RequestLine;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.order.config.FeignConfig;
import com.lx.governance.order.domain.InventoryDeductRequest;

@FeignClient(
        name = "inventory-service",
        configuration = FeignConfig.class,
        fallback = InventoryClient.InventoryClientFallback.class)
public interface InventoryClient {

    @RequestLine("POST /deduct")
    @Headers("Content-Type: application/json")
    ApiResponse<String> deduct(InventoryDeductRequest req);

    @Component
    class InventoryClientFallback implements InventoryClient {
        @Override
        public ApiResponse<String> deduct(InventoryDeductRequest req) {
            return ApiResponse.fail("INVENTORY_UNAVAILABLE", "inventory-service fallback", null);
        }
    }
}
