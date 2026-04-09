package com.lx.governance.inventory.service;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.lx.governance.inventory.domain.DeductRequest;
import com.lx.governance.inventory.repo.StockRepository;

@Service
public class InventoryAppService {

    private final StockRepository stockRepository;
    private final StringRedisTemplate redisTemplate;
    private final boolean failDeduct;

    public InventoryAppService(StockRepository stockRepository,
                               StringRedisTemplate redisTemplate,
                               @Value("${lab.fail.deduct:false}") boolean failDeduct) {
        this.stockRepository = stockRepository;
        this.redisTemplate = redisTemplate;
        this.failDeduct = failDeduct;
    }

    @Transactional
    @SentinelResource(value = "inventory_deduct", blockHandler = "blockDeduct", fallback = "fallbackDeduct")
    public void deduct(DeductRequest req) {
        if (failDeduct) {
            throw new IllegalStateException("lab injected failure: inventory deduct");
        }

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
