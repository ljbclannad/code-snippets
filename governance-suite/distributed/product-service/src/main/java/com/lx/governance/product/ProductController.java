package com.lx.governance.product;

import com.lx.governance.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/products")
public class ProductController {
    private final ProductCatalog catalog;
    public ProductController(ProductCatalog catalog) { this.catalog = catalog; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Product> create(@Valid @RequestBody ProductInput input) {
        return ApiResponse.ok(catalog.create(input), null);
    }

    @PutMapping("/{productId}")
    public ApiResponse<Product> update(@PathVariable String productId, @Valid @RequestBody ProductUpdate input) {
        return ApiResponse.ok(catalog.update(productId, input), null);
    }

    @PostMapping("/{productId}/republish")
    public ApiResponse<Product> republish(@PathVariable String productId) {
        return ApiResponse.ok(catalog.republish(productId), null);
    }

    @GetMapping
    public ApiResponse<ProductCatalog.ProductPage> page(@RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(catalog.page(afterId, limit), null);
    }

    @GetMapping("/{productId}")
    public ApiResponse<Product> get(@PathVariable String productId) {
        return ApiResponse.ok(catalog.get(productId), null);
    }
}
