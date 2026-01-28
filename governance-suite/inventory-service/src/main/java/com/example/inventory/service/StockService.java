package com.example.inventory.service;

import com.example.inventory.dto.StockDeductRequest;
import com.example.inventory.dto.StockDeductResponse;
import com.example.inventory.mapper.StockMapper;
import com.example.inventory.model.StockEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
public class StockService {
    private final StockMapper stockMapper;
    private final StringRedisTemplate redis;

    public StockService(StockMapper stockMapper, StringRedisTemplate redis) {
        this.stockMapper = stockMapper;
        this.redis = redis;
    }

    @Transactional(rollbackFor = Exception.class)
    public StockDeductResponse deduct(StockDeductRequest req) {
        // 幂等：同一个订单只扣一次
        String idem = "stock:deduct:" + req.getOrderNo();
        Boolean ok = redis.opsForValue().setIfAbsent(idem, "1", Duration.ofHours(1));
        if (!Boolean.TRUE.equals(ok)) {
            StockDeductResponse r = new StockDeductResponse();
            r.setSuccess(true);
            r.setCode("OK");
            r.setMessage("idempotent hit");
            return r;
        }

        StockEntity s = stockMapper.findByProductId(req.getProductId());
        if (s == null) {
            StockDeductResponse r = new StockDeductResponse();
            r.setSuccess(false);
            r.setCode("NOT_FOUND");
            r.setMessage("product not found");
            return r;
        }

        int updated = stockMapper.deduct(req.getProductId(), req.getQuantity(), s.getVersion());
        if (updated != 1) {
            StockDeductResponse r = new StockDeductResponse();
            r.setSuccess(false);
            r.setCode("INSUFFICIENT_OR_CONFLICT");
            r.setMessage("insufficient stock or concurrent update");
            return r;
        }

        StockDeductResponse r = new StockDeductResponse();
        r.setSuccess(true);
        r.setCode("OK");
        r.setMessage("deducted");
        return r;
    }
}
