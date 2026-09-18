package com.lx.governance.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@Service
public class ProductCatalog {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ProductCatalog(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(rollbackFor = Exception.class)
    public Product create(ProductInput input) {
        Product product = new Product(input.productId(), input.title(), input.categoryId(), input.brandId(),
                input.priceCents(), input.status(), 1, Instant.now().truncatedTo(ChronoUnit.MICROS));
        try {
            jdbc.update("""
                    INSERT INTO t_product(product_id,title,category_id,brand_id,price_cents,status,version,updated_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, product.productId(), product.title(), product.categoryId(), product.brandId(),
                    product.priceCents(), product.status().name(), product.version(), Timestamp.from(product.updatedAt()));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品 ID 已存在");
        }
        enqueue(product);
        return product;
    }

    @Transactional(rollbackFor = Exception.class)
    public Product update(String productId, ProductUpdate input) {
        Product current = get(productId);
        Product product = new Product(current.productId(), input.title(), input.categoryId(), input.brandId(),
                input.priceCents(), input.status(), input.expectedVersion() + 1, Instant.now().truncatedTo(ChronoUnit.MICROS));
        int affected = jdbc.update("""
                UPDATE t_product SET title=?,category_id=?,brand_id=?,price_cents=?,status=?,version=?,updated_at=?
                WHERE product_id=? AND version=? AND deleted=0
                """, product.title(), product.categoryId(), product.brandId(), product.priceCents(),
                product.status().name(), product.version(), Timestamp.from(product.updatedAt()),
                productId, input.expectedVersion());
        if (affected != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品已被修改，请读取最新版本后重试");
        }
        enqueue(product);
        return product;
    }

    @Transactional(rollbackFor = Exception.class)
    public Product republish(String productId) {
        Product product = get(productId);
        enqueue(product);
        return product;
    }

    public ProductPage page(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "afterId 必须非负，limit 必须在 1 到 500 之间");
        }
        List<ProductRow> rows = jdbc.query("SELECT * FROM t_product WHERE id>? AND deleted=0 ORDER BY id LIMIT ?",
                (row, index) -> new ProductRow(row.getLong("id"), map(row, index)), afterId, limit + 1);
        List<ProductRow> page = rows.stream().limit(limit).toList();
        return new ProductPage(page.stream().map(ProductRow::product).toList(),
                page.isEmpty() ? afterId : page.get(page.size() - 1).id(), rows.size() > limit);
    }

    public record ProductPage(List<Product> items, long nextAfterId, boolean hasMore) { }
    private record ProductRow(long id, Product product) { }

    public Product get(String productId) {
        return jdbc.query("SELECT * FROM t_product WHERE product_id=? AND deleted=0", this::map, productId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在"));
    }

    private Product map(ResultSet row, int index) throws SQLException {
        return new Product(row.getString("product_id"), row.getString("title"), row.getString("category_id"),
                row.getString("brand_id"), row.getLong("price_cents"), Product.Status.valueOf(row.getString("status")),
                row.getLong("version"), row.getTimestamp("updated_at").toInstant());
    }

    private void enqueue(Product product) {
        String eventId = UUID.randomUUID().toString();
        try {
            String payload = json.writeValueAsString(Map.of("eventId", eventId,
                    "eventType", "product.changed", "product", product));
            jdbc.update("INSERT INTO t_product_outbox(event_id,aggregate_id,event_type,payload) VALUES(?,?,?,?)",
                    eventId, product.productId(), "product.changed", payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("商品事件序列化失败", exception);
        }
    }
}
