package com.lx.governance.search;

import java.util.Map;
import java.util.Set;

public record ProductSearchQuery(String q, String categoryId, String brandId, Long minPriceCents,
                                 Long maxPriceCents, Sort sort, int page, int size) {
    public enum Sort { RELEVANCE, PRICE_ASC, PRICE_DESC }
    private static final Set<String> PARAMETERS = Set.of("q", "categoryId", "brandId", "minPriceCents",
            "maxPriceCents", "sort", "page", "size");

    public static ProductSearchQuery from(Map<String, String> params) {
        if (!PARAMETERS.containsAll(params.keySet())) throw new IllegalArgumentException("包含不支持的搜索参数");
        String q = params.getOrDefault("q", "").trim();
        if (q.length() > 100) throw new IllegalArgumentException("搜索词最多 100 个字符");
        String category = id(params.get("categoryId"));
        String brand = id(params.get("brandId"));
        Long min = price(params.get("minPriceCents"));
        Long max = price(params.get("maxPriceCents"));
        if (min != null && max != null && min > max) throw new IllegalArgumentException("最低价格不能大于最高价格");
        try {
            int page = Integer.parseInt(params.getOrDefault("page", "1"));
            int size = Integer.parseInt(params.getOrDefault("size", "20"));
            if (page < 1 || size < 1 || size > 100 || (long) page * size > 10000) {
                throw new IllegalArgumentException("页码从 1 开始，单页 1–100 条，结果窗口最多 10000 条");
            }
            final Sort sort;
            try {
                sort = Sort.valueOf(params.getOrDefault("sort", "RELEVANCE"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("排序仅支持 RELEVANCE、PRICE_ASC、PRICE_DESC");
            }
            return new ProductSearchQuery(q, category, brand, min, max, sort, page, size);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("页码和每页数量必须为整数");
        }
    }

    private static String id(String value) {
        if (value == null) return null;
        if (!value.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("类目或品牌 ID 格式无效");
        return value;
    }

    private static Long price(String value) {
        if (value == null) return null;
        try {
            long price = Long.parseLong(value);
            if (price < 0 || price > 999999999999L) throw new IllegalArgumentException("价格超出支持范围");
            return price;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("价格必须是以分为单位的整数");
        }
    }
}
