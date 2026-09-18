package com.lx.governance.search;

import java.util.Map;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductSearchController {
    private final ElasticsearchProductIndex index;

    public ProductSearchController(ElasticsearchProductIndex index) { this.index = index; }

    @GetMapping("/products")
    public ApiResponse<ProductSearchPage> search(@RequestParam Map<String, String> params) {
        return ApiResponse.ok(index.search(ProductSearchQuery.from(params)), Trace.getOrCreate());
    }
}
