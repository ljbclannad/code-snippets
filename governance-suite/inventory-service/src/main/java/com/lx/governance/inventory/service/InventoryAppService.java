package com.lx.governance.inventory.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.lx.governance.inventory.domain.DeductRequest;
import com.lx.governance.inventory.repo.StockRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
public class InventoryAppService {

    private final StockRepository stockRepository;
    private final StringRedisTemplate redisTemplate;

    public InventoryAppService(StockRepository stockRepository, StringRedisTemplate redisTemplate) {
        this.stockRepository = stockRepository;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    @SentinelResource(value = "inventory_deduct", blockHandler = "blockDeduct", fallback = "fallbackDeduct")
    public void deduct(DeductRequest req) {
        String idemKey = "idem:stock:" + req.getOrderNo();
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(idemKey, "1", Duration.ofHours(1));
        if (!Boolean.TRUE.equals(ok)) {
            return; // already deducted
        }

        int tries = 5;
        while (tries-- > 0) {
            StockRepository.StockRow row = stockRepository.find(req.getProductId());
            if (row == null) {
                throw new IllegalArgumentException("product not found");
            }
            if (row.available < req.getQuantity()) {
                throw new IllegalArgumentException("insufficient stock");
            }
            int updated = stockRepository.deductWithVersion(req.getProductId(), req.getQuantity(), row.version);
            if (updated > 0) {
                return;
            }
        }
        throw new IllegalArgumentException("stock busy, retry later");
    }

    public void blockDeduct(DeductRequest req, com.alibaba.csp.sentinel.slots.block.BlockException ex) {
        throw new IllegalArgumentException("inventory_deduct blocked: " + ex.getClass().getSimpleName());
    }

    public void fallbackDeduct(DeductRequest req, Throwable ex) {
        throw new IllegalArgumentException("inventory_deduct fallback: " + ex.getMessage());
    }
}
