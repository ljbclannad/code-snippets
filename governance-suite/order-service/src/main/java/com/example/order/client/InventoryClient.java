package com.example.order.client;

import com.example.order.dto.StockDeductRequest;
import com.example.order.dto.StockDeductResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "inventory-service", fallbackFactory = InventoryClientFallbackFactory.class)
public interface InventoryClient {
    @PostMapping("/stock/deduct")
    StockDeductResponse deduct(@RequestBody StockDeductRequest req);
}
