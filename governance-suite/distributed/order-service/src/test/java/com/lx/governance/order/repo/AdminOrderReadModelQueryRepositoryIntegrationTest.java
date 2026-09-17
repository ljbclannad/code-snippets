package com.lx.governance.order.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lx.governance.order.search.OrderOperatorOrder;
import com.lx.governance.order.search.OrderOperatorSearchCriteria;
import com.lx.governance.order.search.OrderOperatorSearchCursor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdminOrderReadModelQueryRepositoryIntegrationTest {
    private static final String ORDER_NO_PREFIX = "OPS-";

    private JdbcTemplate jdbcTemplate;
    private AdminOrderReadModelQueryRepository repository;
    private String orderNoPrefix;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl("jdbc:mysql://%s:%s/order_db?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useAffectedRows=true"
                .formatted(env("MYSQL_HOST", "127.0.0.1"), env("MYSQL_PORT", "3306")));
        dataSource.setUsername(env("MYSQL_USER", "root"));
        dataSource.setPassword(env("MYSQL_PASS", "root"));

        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new AdminOrderReadModelQueryRepository(jdbcTemplate);
        orderNoPrefix = ORDER_NO_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-";
    }

    @AfterEach
    void removesLocalMysqlVerificationData() {
        jdbcTemplate.update("DELETE FROM t_order_admin_read_model WHERE order_no LIKE ?", orderNoPrefix + "%");
    }

    @Test
    void filtersOrdersAndReadsStableCursorPagesWithoutDuplicatesOrGaps() {
        Instant createdAt = Instant.parse("2026-09-15T10:00:00Z");
        insert("06", createdAt.plusSeconds(1), "token-match", "product-match", "CREATED", "PREPARED", 100L, 1);
        insert("05", createdAt, "token-match", "product-match", "CREATED", "PREPARED", 150L, 1);
        insert("04", createdAt, "token-match", "product-match", "CREATED", "PREPARED", 180L, 1);
        insert("03", createdAt.minusSeconds(1), "token-match", "product-match", "CREATED", "PREPARED", 200L, 1);
        insert("02", createdAt.minusSeconds(2), "token-match", "product-match", "CREATED", "PREPARED", 220L, 1);
        insert("01", createdAt.minusSeconds(3), "token-match", "product-match", "CREATED", "PREPARED", 250L, 1);
        insert("product-mismatch", createdAt, "token-match", "other-product", "CREATED", "PREPARED", 150L, 1);
        insert("amount-mismatch", createdAt, "token-match", "product-match", "CREATED", "PREPARED", 99L, 1);
        insert("placeholder", createdAt, "token-match", "product-match", "CREATED", "PREPARED", 150L, 0);
        insert("missing-created-at", null, "token-match", "product-match", "CREATED", "PREPARED", 150L, 1);

        OrderOperatorSearchCriteria criteria = OrderOperatorSearchCriteria.create(null, "token-match", "product-match",
                "CREATED", "PREPARED", createdAt.minusSeconds(10), createdAt.plusSeconds(10), 100L, 250L, 2);

        List<String> orderNos = new ArrayList<>();
        OrderOperatorSearchCursor cursor = null;
        while (true) {
            List<OrderOperatorOrder> rows = repository.find(criteria, cursor);
            List<OrderOperatorOrder> page = rows.size() > criteria.limit() ? rows.subList(0, criteria.limit()) : rows;
            orderNos.addAll(page.stream().map(OrderOperatorOrder::orderNo).toList());
            if (rows.size() <= criteria.limit()) {
                break;
            }
            OrderOperatorOrder last = page.get(page.size() - 1);
            cursor = new OrderOperatorSearchCursor(last.createdAt().toString(), last.orderNo());
        }

        assertThat(orderNos).containsExactly(orderNo("06"), orderNo("05"), orderNo("04"), orderNo("03"), orderNo("02"),
                orderNo("01"));
        assertThat(orderNos).doesNotHaveDuplicates();
    }

    private void insert(String suffix, Instant createdAt, String customerSearchToken, String productId, String status,
            String paymentStatus, long amountCents, long orderVersion) {
        jdbcTemplate.update("""
                INSERT INTO t_order_admin_read_model
                    (order_no, customer_search_token, product_id, quantity, amount_cents, status, order_version,
                     created_at, payment_status, projected_at)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, NOW())
                """, orderNo(suffix), customerSearchToken, productId, amountCents, status, orderVersion, createdAt == null ? null : java.sql.Timestamp.from(createdAt), paymentStatus);
    }

    private String orderNo(String suffix) {
        return orderNoPrefix + suffix;
    }

    private String env(String name, String defaultValue) {
        return System.getenv().getOrDefault(name, defaultValue);
    }
}
