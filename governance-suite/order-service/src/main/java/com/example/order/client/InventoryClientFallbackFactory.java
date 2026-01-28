package com.example.order.client;

import com.example.order.dto.StockDeductRequest;
import com.example.order.dto.StockDeductResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class InventoryClientFallbackFactory implements FallbackFactory<InventoryClient> {
    private static final Logger log = LoggerFactory.getLogger(InventoryClientFallbackFactory.class);

    @Override
    public InventoryClient create(Throwable cause) {
        return req -> {
            log.warn("InventoryClient fallback triggered: {}", cause.toString());
            StockDeductResponse r = new StockDeductResponse();
            r.setSuccess(false);
            r.setCode("INVENTORY_DOWN");
            r.setMessage("inventory-service unavailable (fallback)");
            return r;
        };
    }
}
