package com.spring.simulation.constructor;

import com.spring.simulation.ioc.Autowired;
import com.spring.simulation.ioc.Component;

@Component
public class ProductService {
    private final ProductRepository productRepository;
    private final PriceCalculator priceCalculator;

    @Autowired
    public ProductService(ProductRepository productRepository, PriceCalculator priceCalculator) {
        this.productRepository = productRepository;
        this.priceCalculator = priceCalculator;
        System.out.println("    [ProductService] Constructor called with dependencies injected");
    }

    public void createProduct(String name, double price) {
        double finalPrice = priceCalculator.calculate(price);
        System.out.println("    [ProductService] Creating product: " + name + " with price: " + finalPrice);
        productRepository.save(name, finalPrice);
    }
}
