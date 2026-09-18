package com.lx.governance.search;

import java.util.List;

public record ProductSearchPage(List<Item> items, long total, int page, int size, boolean hasNext) {
    public record Item(String productId, String title, String categoryId, String brandId, long priceCents) { }
}
