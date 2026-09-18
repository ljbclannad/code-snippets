package com.lx.governance.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 必须显式提供隔离 ES 地址；测试索引使用随机名称，禁止默认连接业务 ES。
@EnabledIfEnvironmentVariable(named = "PRODUCT_SEARCH_TEST_ES_URL", matches = ".+")
class ProductSearchElasticsearchIntegrationTest {
    @Test
    void realChineseSearchFiltersPagesAndKeepsOffSaleAgainstStaleMessages() throws Exception {
        RestClient client = RestClient.create(System.getenv("PRODUCT_SEARCH_TEST_ES_URL"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String name = "product-search-test-" + UUID.randomUUID();
        ElasticsearchProductIndex index = new ElasticsearchProductIndex(client, mapper, name);
        index.initialize();
        index.initialize();
        index.project(product("a", "男士夏季透气跑步鞋", "shoes", "sport", 19900, "ON_SALE", 1));
        index.project(product("b", "轻便透气跑步鞋", "shoes", "sport", 29900, "ON_SALE", 1));
        index.project(product("c", "透气跑步鞋", "shoes", "other", 9900, "ON_SALE", 1));
        index.project(product("d", "透气跑步鞋", "shoes", "sport", 9900, "OFF_SALE", 1));
        index.project(product("e", "跑步鞋清洗工具", "tools", "sport", 19900, "ON_SALE", 1));
        client.post().uri("/{index}/_refresh", name).retrieve().toBodilessEntity();
        MockMvc http = MockMvcBuilders.standaloneSetup(new ProductSearchController(index))
                .setControllerAdvice(new SearchExceptionHandler()).build();
        // 关键词顺序与标题不同，证明使用分词而非连续子串匹配。
        http.perform(get("/products").param("q", "跑步鞋 透气").param("categoryId", "shoes")
                        .param("brandId", "sport").param("minPriceCents", "19900").param("maxPriceCents", "29900")
                        .param("sort", "PRICE_ASC").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].productId").value("a"))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        http.perform(get("/products").param("q", "跑步鞋 透气").param("categoryId", "shoes")
                        .param("brandId", "sport").param("sort", "PRICE_ASC").param("size", "1").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].productId").value("b"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        http.perform(get("/products").param("q", "跑步鞋 透气").param("brandId", "sport").param("sort", "PRICE_DESC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].productId").value("b"));
        http.perform(get("/products").param("q", "跑步鞋 透气"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
        index.project(product("a", "更名商品", "shoes", "sport", 9900, "OFF_SALE", 2));
        assertThat(index.project(product("a", "旧跑步鞋", "shoes", "sport", 19900, "ON_SALE", 1))).isFalse();
        assertThat(index.project(product("a", "更名商品", "shoes", "sport", 9900, "OFF_SALE", 2))).isFalse();
        client.post().uri("/{index}/_refresh", name).retrieve().toBodilessEntity();
        http.perform(get("/products").param("categoryId", "shoes").param("brandId", "sport"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].productId").value("b"));
        assertThat(index.project(product("a", "全新篮球鞋", "shoes", "sport", 9900, "ON_SALE", 3))).isTrue();
        client.post().uri("/{index}/_refresh", name).retrieve().toBodilessEntity();
        http.perform(get("/products").param("q", "篮球鞋"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].priceCents").value(9900));
    }

    private ProductDocument product(String id, String title, String category, String brand, long price, String status, long version) {
        return new ProductDocument(id, title, category, brand, price, status, version, Instant.parse("2026-09-18T00:00:00Z"));
    }
}
