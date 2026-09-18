package com.lx.governance.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductSearchHttpTest {
    @Test
    void finalAllowedWindowDoesNotAdvertiseAnInvalidNextPage() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://es");
        MockRestServiceServer es = MockRestServiceServer.bindTo(builder).build();
        es.expect(requestTo("http://es/products-test/_search?allow_partial_search_results=false"))
                .andRespond(withSuccess("""
                        {"timed_out":false,"_shards":{"failed":0},
                        "hits":{"total":{"value":15000,"relation":"eq"},"hits":[]}}
                        """, MediaType.APPLICATION_JSON));
        MockMvc http = MockMvcBuilders.standaloneSetup(new ProductSearchController(
                        new ElasticsearchProductIndex(builder.build(), new ObjectMapper(), "products-test")))
                .setControllerAdvice(new SearchExceptionHandler()).build();
        http.perform(get("/products").param("page", "333").param("size", "30"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hasNext").value(false));
        es.verify();
    }

    @Test
    void unavailableAndPartialSearchNeverPretendToBeEmptySuccess() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://es");
        MockRestServiceServer es = MockRestServiceServer.bindTo(builder).build();
        String url = "http://es/products-test/_search?allow_partial_search_results=false";
        es.expect(requestTo(url)).andRespond(withServerError());
        es.expect(requestTo(url)).andRespond(withSuccess("""
                {"timed_out":true,"hits":{"total":{"value":0,"relation":"eq"},"hits":[]}}
                """, MediaType.APPLICATION_JSON));
        es.expect(requestTo(url)).andRespond(withSuccess("""
                {"timed_out":false,"_shards":{"failed":1},"hits":{"total":{"value":0,"relation":"eq"},"hits":[]}}
                """, MediaType.APPLICATION_JSON));
        MockMvc http = MockMvcBuilders.standaloneSetup(new ProductSearchController(
                        new ElasticsearchProductIndex(builder.build(), new ObjectMapper(), "products-test")))
                .setControllerAdvice(new SearchExceptionHandler()).build();
        for (int attempt = 0; attempt < 3; attempt++) {
            http.perform(get("/products")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"));
        }
        es.verify();
    }
    @Test
    void buyerSearchCombinesTitleFiltersAndStablePricePagination() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://es");
        MockRestServiceServer es = MockRestServiceServer.bindTo(builder).build();
        es.expect(requestTo("http://es/products-test/_search?allow_partial_search_results=false"))
                .andExpect(content().json("""
                        {"from":2,"size":2,"track_total_hits":true,
                         "query":{"bool":{"must":[{"match":{"title":{"query":"跑步鞋","operator":"and"}}}],
                           "filter":[{"term":{"status":"ON_SALE"}},{"term":{"categoryId":"shoes"}},
                           {"term":{"brandId":"brand-a"}},{"range":{"priceCents":{"gte":10000,"lte":30000}}}]}},
                         "sort":[{"priceCents":"asc"},{"productId":"asc"}]}
                        """))
                .andRespond(withSuccess("""
                        {"timed_out":false,"_shards":{"failed":0},"hits":{"total":{"value":3,"relation":"eq"},"hits":[
                        {"_source":{"productId":"shoe-3","title":"轻便跑步鞋","categoryId":"shoes","brandId":"brand-a","priceCents":25000}}]}}
                        """, MediaType.APPLICATION_JSON));
        MockMvc http = MockMvcBuilders.standaloneSetup(new ProductSearchController(
                        new ElasticsearchProductIndex(builder.build(), new ObjectMapper(), "products-test")))
                .setControllerAdvice(new SearchExceptionHandler()).build();
        http.perform(get("/products").param("q", "跑步鞋").param("categoryId", "shoes")
                        .param("brandId", "brand-a").param("minPriceCents", "10000").param("maxPriceCents", "30000")
                        .param("sort", "PRICE_ASC").param("page", "2").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].productId").value("shoe-3"))
                .andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.hasNext").value(false));
        es.verify();
    }
    @Test
    void buyerCannotRequestOffSaleProductsOrUnboundedPages() throws Exception {
        MockMvc http = MockMvcBuilders.standaloneSetup(new ProductSearchController(
                        new ElasticsearchProductIndex(RestClient.create("http://localhost:1"), new ObjectMapper(), "products-test")))
                .setControllerAdvice(new SearchExceptionHandler()).build();
        http.perform(get("/products").param("status", "OFF_SALE")).andExpect(status().isBadRequest());
        http.perform(get("/products").param("page", "0")).andExpect(status().isBadRequest());
        http.perform(get("/products").param("page", "2147483647")).andExpect(status().isBadRequest());
        http.perform(get("/products").param("size", "101")).andExpect(status().isBadRequest());
        http.perform(get("/products").param("minPriceCents", "-1")).andExpect(status().isBadRequest());
        http.perform(get("/products").param("minPriceCents", "200").param("maxPriceCents", "100")).andExpect(status().isBadRequest());
        http.perform(get("/products").param("sort", "sales")).andExpect(status().isBadRequest());
        http.perform(get("/products").param("categoryId", "鞋类")).andExpect(status().isBadRequest());
        http.perform(get("/products").param("q", "鞋".repeat(101))).andExpect(status().isBadRequest());
    }
}
