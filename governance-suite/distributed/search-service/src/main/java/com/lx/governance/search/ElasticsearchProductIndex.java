package com.lx.governance.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.core.io.ClassPathResource;
import java.io.InputStream;
import java.io.IOException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ElasticsearchProductIndex {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String index;

    public ElasticsearchProductIndex(RestClient client, ObjectMapper mapper, String index) {
        if (!index.matches("[a-z][a-z0-9_-]{0,80}")) throw new IllegalArgumentException("商品索引名称无效");
        this.client = client;
        this.mapper = mapper;
        this.index = index;
    }

    public void initialize() {
        try {
            client.head().uri("/{index}", index).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound missing) {
            try (InputStream input = new ClassPathResource("product-index.json").getInputStream()) {
                JsonNode definition = mapper.readTree(input);
                client.put().uri("/{index}", index).contentType(MediaType.APPLICATION_JSON)
                        .body(definition).retrieve().toBodilessEntity();
            } catch (HttpClientErrorException.BadRequest concurrentCreate) {
                try {
                    if (!"resource_already_exists_exception".equals(mapper.readTree(concurrentCreate.getResponseBodyAsString())
                            .path("error").path("type").asText())) throw new SearchUnavailableException(concurrentCreate);
                } catch (IOException invalidResponse) {
                    throw new SearchUnavailableException(invalidResponse);
                }
            } catch (Exception exception) {
                throw new SearchUnavailableException(exception);
            }
        }
        try {
            JsonNode mapping = mapper.readTree(client.get().uri("/{index}/_mapping", index).retrieve().body(String.class));
            JsonNode properties = mapping.required(index).path("mappings").path("properties");
            Map<String, String> types = Map.of("productId", "keyword", "title", "text", "categoryId", "keyword",
                    "brandId", "keyword", "status", "keyword", "priceCents", "long", "version", "long", "updatedAt", "date");
            boolean invalidType = types.entrySet().stream()
                    .anyMatch(field -> !field.getValue().equals(properties.path(field.getKey()).path("type").asText()));
            if (invalidType || !"smartcn".equals(properties.path("title").path("analyzer").asText())
                    || !"smartcn".equals(properties.path("title").path("search_analyzer").asText("smartcn"))) {
                throw new IllegalStateException("已有商品索引映射不兼容，请创建新索引并从商品事实重放");
            }
        } catch (Exception exception) {
            throw new SearchUnavailableException(exception);
        }
    }

    public ProductSearchPage search(ProductSearchQuery query) {
        List<Object> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("status", "ON_SALE")));
        if (query.categoryId() != null) filters.add(Map.of("term", Map.of("categoryId", query.categoryId())));
        if (query.brandId() != null) filters.add(Map.of("term", Map.of("brandId", query.brandId())));
        Map<String, Long> price = new LinkedHashMap<>();
        if (query.minPriceCents() != null) price.put("gte", query.minPriceCents());
        if (query.maxPriceCents() != null) price.put("lte", query.maxPriceCents());
        if (!price.isEmpty()) filters.add(Map.of("range", Map.of("priceCents", price)));
        Object title = query.q().isEmpty() ? Map.of("match_all", Map.of())
                : Map.of("match", Map.of("title", Map.of("query", query.q(), "operator", "and")));
        Object primarySort = switch (query.sort()) {
            case RELEVANCE -> Map.of("_score", "desc");
            case PRICE_ASC -> Map.of("priceCents", "asc");
            case PRICE_DESC -> Map.of("priceCents", "desc");
        };
        Map<String, Object> body = Map.of("from", (query.page() - 1) * query.size(), "size", query.size(),
                "track_total_hits", true, "timeout", "2s",
                "query", Map.of("bool", Map.of("must", List.of(title), "filter", filters)),
                "sort", List.of(primarySort, Map.of("productId", "asc")));
        try {
            String response = client.post().uri("/{index}/_search?allow_partial_search_results=false", index)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
            JsonNode root = mapper.readTree(response);
            if (root.path("timed_out").asBoolean() || root.path("_shards").path("failed").asInt() > 0
                    || !root.path("hits").path("hits").isArray()
                    || !"eq".equals(root.path("hits").path("total").path("relation").asText())) {
                throw new IllegalStateException("搜索结果不完整");
            }
            List<ProductSearchPage.Item> items = new ArrayList<>();
            for (JsonNode hit : root.path("hits").path("hits")) {
                JsonNode source = hit.path("_source");
                items.add(new ProductSearchPage.Item(source.required("productId").asText(), source.required("title").asText(),
                        source.required("categoryId").asText(), source.required("brandId").asText(), source.required("priceCents").asLong()));
            }
            long total = root.path("hits").path("total").required("value").asLong();
            return new ProductSearchPage(List.copyOf(items), total, query.page(), query.size(),
                    (long) query.page() * query.size() < total && (long) (query.page() + 1) * query.size() <= 10000);
        } catch (Exception exception) {
            throw new SearchUnavailableException(exception);
        }
    }

    // 下架保留完整文档及外部版本，避免过期上架消息把商品重新暴露。
    public boolean project(ProductDocument product) {
        try {
            client.put().uri("/{index}/_doc/{id}?version={version}&version_type=external",
                            index, product.productId(), product.version())
                    .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(product))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.Conflict exception) {
            try {
                if ("version_conflict_engine_exception".equals(mapper.readTree(exception.getResponseBodyAsString())
                        .path("error").path("type").asText())) return false;
            } catch (Exception invalidResponse) {
                throw new SearchUnavailableException(invalidResponse);
            }
            throw new SearchUnavailableException(exception);
        } catch (Exception exception) {
            throw new SearchUnavailableException(exception);
        }
    }
}
