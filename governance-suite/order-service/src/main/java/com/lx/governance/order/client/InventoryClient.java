package com.lx.governance.order.client;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.order.domain.InventoryDeductRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "inventory-service", path = "/", fallback = InventoryClient.InventoryClientFallback.class)
public interface InventoryClient {

    @PostMapping("/deduct")
    ApiResponse<String> deduct(@RequestBody InventoryDeductRequest req);

    @Component
    class InventoryClientFallback implements InventoryClient {
        @Override
        public ApiResponse<String> deduct(InventoryDeductRequest req) {
            return ApiResponse.fail("INVENTORY_UNAVAILABLE", "inventory-service fallback", null);
        }
    }
}
