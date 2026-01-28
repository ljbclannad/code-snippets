package com.example.inventory.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.example.inventory.dto.StockDeductRequest;
import com.example.inventory.dto.StockDeductResponse;
import com.example.inventory.service.StockService;
import org.springframework.web.bind.annotation.*;

@RestController
public class StockController {
    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @PostMapping("/stock/deduct")
    @SentinelResource(value = "stockDeduct", blockHandler = "block")
    public StockDeductResponse deduct(@RequestBody StockDeductRequest req) {
        return stockService.deduct(req);
    }

    public StockDeductResponse block(StockDeductRequest req, BlockException ex) {
        StockDeductResponse r = new StockDeductResponse();
        r.setSuccess(false);
        r.setCode("SENTINEL_BLOCK");
        r.setMessage("blocked: " + ex.getClass().getSimpleName());
        return r;
    }
}
