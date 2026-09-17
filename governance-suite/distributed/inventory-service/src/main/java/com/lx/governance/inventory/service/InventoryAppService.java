package com.lx.governance.inventory.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.lx.governance.common.monitoring.annotation.Monitored;
import com.lx.governance.inventory.domain.DeductRequest;
import com.lx.governance.inventory.domain.InventorySnapshot;
import com.lx.governance.inventory.domain.InventorySnapshotBatchRequest;
import com.lx.governance.inventory.repo.StockRepository;
import io.micrometer.core.instrument.MeterRegistry;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class InventoryAppService {

    private static final Logger log = LoggerFactory.getLogger(InventoryAppService.class);

    private final StockRepository stockRepository;
    private final StringRedisTemplate redisTemplate;
    private final boolean failDeduct;
    private final MeterRegistry meterRegistry;

    public InventoryAppService(StockRepository stockRepository,
                               StringRedisTemplate redisTemplate,
                               @Value("${lab.fail.deduct:false}") boolean failDeduct,
                               MeterRegistry meterRegistry) {
        this.stockRepository = stockRepository;
        this.redisTemplate = redisTemplate;
        this.failDeduct = failDeduct;
        this.meterRegistry = meterRegistry;
    }

    @Monitored(value = "inventory.deduct", description = "库存扣减")
    @Transactional
    @SentinelResource(value = "inventory_deduct", blockHandler = "blockDeduct", fallback = "fallbackDeduct")
    public void deduct(DeductRequest req) {
        log.info("Stock deduction started",
            kv("event", "stock_deduct_start"),
            kv("productId", req.getProductId()),
            kv("quantity", req.getQuantity()),
            kv("orderNo", req.getOrderNo())
        );

        if (failDeduct) {
            throw new IllegalStateException("lab injected failure: inventory deduct");
        }

        String idemKey = "idem:stock:" + req.getOrderNo();
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(idemKey, "1", Duration.ofHours(1));
        if (!Boolean.TRUE.equals(ok)) {
            log.info("Stock deduction skipped - idempotency check",
                kv("event", "stock_deduct_idempotent"),
                kv("productId", req.getProductId()),
                kv("orderNo", req.getOrderNo()),
                kv("status", "ALREADY_PROCESSED")
            );
            return; // already deducted
        }

        int tries = 5;
        while (tries-- > 0) {
            if (tries < 4) {
                // Record retry attempts (not the first attempt)
                meterRegistry.counter("inventory.deduct.retry").increment();
            }
            StockRepository.StockRow row = stockRepository.find(req.getProductId());
            if (row == null) {
                throw new IllegalArgumentException("product not found");
            }
            if (row.available < req.getQuantity()) {
                log.warn("Stock deduction failed - insufficient stock",
                    kv("event", "stock_deduct_failed"),
                    kv("productId", req.getProductId()),
                    kv("requestedQuantity", req.getQuantity()),
                    kv("availableStock", row.available),
                    kv("orderNo", req.getOrderNo()),
                    kv("status", "INSUFFICIENT_STOCK")
                );
                throw new IllegalArgumentException("insufficient stock");
            }
            int updated = stockRepository.deductWithVersion(req.getProductId(), req.getQuantity(), row.version);
            if (updated > 0) {
                log.info("Stock deducted successfully",
                    kv("event", "stock_deducted"),
                    kv("productId", req.getProductId()),
                    kv("quantity", req.getQuantity()),
                    kv("orderNo", req.getOrderNo()),
                    kv("status", "SUCCESS")
                );
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

    public List<InventorySnapshot> snapshots(InventorySnapshotBatchRequest request) {
        if (request == null || request.productIds() == null) {
            throw new IllegalArgumentException("productIds must contain between 1 and 100 unique values");
        }
        List<String> productIds = request.productIds().stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (productIds.isEmpty() || productIds.size() > 100) {
            throw new IllegalArgumentException("productIds must contain between 1 and 100 unique values");
        }
        Instant observedAt = Instant.now();
        return stockRepository.findByProductIds(productIds).stream()
                .map(row -> new InventorySnapshot(row.productId, row.available, observedAt)).toList();
    }
}
