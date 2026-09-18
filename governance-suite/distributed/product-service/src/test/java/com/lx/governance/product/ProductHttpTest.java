package com.lx.governance.product;

import com.lx.governance.common.auth.AuthTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:products;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false", "spring.cloud.bootstrap.enabled=false",
        "app.product.admin-user-ids=42", "app.auth.token-secret=test-secret"})
@AutoConfigureMockMvc
class ProductHttpTest {
    @Autowired MockMvc http;
    @Autowired AuthTokenService tokens;
    @Autowired JdbcTemplate database;
    @Autowired ObjectMapper json;

    @Test
    void administratorCanCreateAndReadProduct() throws Exception {
        String token = tokens.issueToken(42, "商品管理员");
        http.perform(post("/admin/products").header("Authorization", token).contentType("application/json")
                .content("{\"productId\":\"shoe-1\",\"title\":\"透气跑步鞋\",\"categoryId\":\"shoe\",\"brandId\":\"brand\",\"priceCents\":29900,\"status\":\"ON_SALE\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.version").value(1));
        http.perform(get("/admin/products/shoe-1").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("透气跑步鞋"));
    }

    @Test
    void updateRequiresCurrentVersionAndCanTakeProductOffSale() throws Exception {
        String token = tokens.issueToken(42, "商品管理员");
        http.perform(post("/admin/products").header("Authorization", token).contentType("application/json")
                .content(productBody("versioned"))).andExpect(status().isCreated());
        String update = "{\"title\":\"改名商品\",\"categoryId\":\"shoe\",\"brandId\":\"brand\",\"priceCents\":19900,\"status\":\"OFF_SALE\",\"expectedVersion\":1}";
        http.perform(put("/admin/products/versioned").header("Authorization", token)
                .contentType("application/json").content(update)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2)).andExpect(jsonPath("$.data.status").value("OFF_SALE"));
        http.perform(put("/admin/products/versioned").header("Authorization", token)
                .contentType("application/json").content(update)).andExpect(status().isConflict());
        http.perform(get("/admin/products/versioned").header("Authorization", token))
                .andExpect(jsonPath("$.data.priceCents").value(19900));
    }

    @Test
    void republishKeepsFactVersionAndRejectsMissingProduct() throws Exception {
        String token = tokens.issueToken(42, "商品管理员");
        http.perform(post("/admin/products").header("Authorization", token).contentType("application/json")
                .content(productBody("replay"))).andExpect(status().isCreated());
        for (int attempt = 0; attempt < 2; attempt++) {
            http.perform(post("/admin/products/replay/republish").header("Authorization", token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        }
        http.perform(post("/admin/products/missing/republish").header("Authorization", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void administratorCanPageFactsForRebuild() throws Exception {
        String token = tokens.issueToken(42, "商品管理员");
        http.perform(post("/admin/products").header("Authorization", token).contentType("application/json")
                .content(productBody("page-item"))).andExpect(status().isCreated());
        http.perform(get("/admin/products").param("afterId", "0").param("limit", "1")
                .header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.nextAfterId").isNumber());
        http.perform(get("/admin/products").param("limit", "501").header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ordinaryUserAndSpoofedHeadersCannotMaintainProducts() throws Exception {
        http.perform(post("/admin/products").header("Authorization", tokens.issueToken(7, "普通用户"))
                .header("X-User-Id", "42").contentType("application/json").content(productBody("forbidden")))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidProductIsRejected() throws Exception {
        String token = tokens.issueToken(42, "商品管理员");
        http.perform(post("/admin/products").header("Authorization", token).contentType("application/json")
                .content(productBody("../invalid"))).andExpect(status().isBadRequest());
        http.perform(post("/admin/products").header("Authorization", token).contentType("application/json")
                .content(productBody("negative").replace("29900", "-1"))).andExpect(status().isBadRequest());
        http.perform(post("/admin/products").header("Authorization", token).contentType("application/json")
                .content(productBody("excess").replace("29900", "1000000000000"))).andExpect(status().isBadRequest());
    }

    @Test
    void productAndOutboxCommitTogetherAndFailedOutboxRollsBackFact() throws Exception {
        String token = tokens.issueToken(42, "商品管理员");
        http.perform(post("/admin/products").header("Authorization", token).contentType("application/json")
                .content(productBody("atomic"))).andExpect(status().isCreated());
        // Outbox 是发布服务消费的外部协议边界。
        String payload = database.queryForObject("SELECT payload FROM t_product_outbox WHERE aggregate_id='atomic'", String.class);
        var event = json.readTree(payload);
        assertEquals("product.changed", event.path("eventType").asText());
        assertEquals("atomic", event.path("product").path("productId").asText());
        assertTrue(event.path("product").path("updatedAt").isTextual());
        database.execute("ALTER TABLE t_product_outbox ADD CONSTRAINT reject_test_event CHECK (aggregate_id <> 'rollback')");
        try {
            assertThrows(Exception.class, () -> http.perform(post("/admin/products").header("Authorization", token)
                    .contentType("application/json").content(productBody("rollback"))));
            http.perform(get("/admin/products/rollback").header("Authorization", token)).andExpect(status().isNotFound());
        } finally {
            database.execute("ALTER TABLE t_product_outbox DROP CONSTRAINT reject_test_event");
        }
    }

    @Test
    void categoryAndBrandIdsMustUseTheSearchEventAlphabet() throws Exception {
        String token = tokens.issueToken(42, "商品管理员");
        http.perform(post("/admin/products").header("Authorization", token).contentType("application/json")
                .content(productBody("valid-ids"))).andExpect(status().isCreated());
        for (String invalid : new String[]{"中文", "with space", "a/b"}) {
            for (String fieldValue : new String[]{"shoe", "brand"}) {
                String create = productBody("invalid-ids").replace("\"" + fieldValue + "\"", "\"" + invalid + "\"");
                http.perform(post("/admin/products").header("Authorization", token).contentType("application/json")
                        .content(create)).andExpect(status().isBadRequest());
                String update = create.replace("\"productId\":\"invalid-ids\",", "\"expectedVersion\":1,");
                http.perform(put("/admin/products/valid-ids").header("Authorization", token).contentType("application/json")
                        .content(update)).andExpect(status().isBadRequest());
            }
        }
    }

    private String productBody(String id) {
        return "{\"productId\":\"" + id + "\",\"title\":\"透气跑步鞋\",\"categoryId\":\"shoe\",\"brandId\":\"brand\",\"priceCents\":29900,\"status\":\"ON_SALE\"}";
    }

    @Test
    void anonymousCannotMaintainProducts() throws Exception {
        http.perform(post("/admin/products").contentType("application/json")
                .content("{\"productId\":\"shoe-1\",\"title\":\"透气跑步鞋\",\"categoryId\":\"shoe\",\"brandId\":\"brand\",\"priceCents\":29900,\"status\":\"ON_SALE\"}"))
                .andExpect(status().isUnauthorized());
    }
}
