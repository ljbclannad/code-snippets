package com.lx.governance.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.order.domain.InventoryDeductRequest;

@FeignClient(
        name = "inventory-service",
        fallback = InventoryClient.InventoryClientFallback.class)
public interface InventoryClient {

    @PostMapping("/deduct")
    ApiResponse<String> deduct(@RequestBody InventoryDeductRequest req);

    @PostMapping("/tcc/try")
    ApiResponse<String> tryDeductTcc(@RequestBody InventoryDeductRequest req);

    @Component
    class InventoryClientFallback implements InventoryClient {
        @Override
        public ApiResponse<String> deduct(InventoryDeductRequest req) {
            return ApiResponse.fail("INVENTORY_UNAVAILABLE", "inventory-service fallback", null);
        }

        @Override
        public ApiResponse<String> tryDeductTcc(InventoryDeductRequest req) {
            return ApiResponse.fail("INVENTORY_UNAVAILABLE", "inventory-service fallback", null);
        }
    }
}
