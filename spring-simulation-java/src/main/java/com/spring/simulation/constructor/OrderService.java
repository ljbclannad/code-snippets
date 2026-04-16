package com.spring.simulation.constructor;

import com.spring.simulation.ioc.Autowired;
import com.spring.simulation.ioc.Component;

@Component
public class OrderService {
    private ProductRepository productRepository;
    private PriceCalculator priceCalculator;

    public OrderService() {
        System.out.println("    [OrderService] Constructor called (no-arg)");
    }

    @Autowired
    public void setProductRepository(ProductRepository productRepository) {
        this.productRepository = productRepository;
        System.out.println("    [OrderService] setProductRepository called");
    }

    @Autowired
    public void setPriceCalculator(PriceCalculator priceCalculator) {
        this.priceCalculator = priceCalculator;
        System.out.println("    [OrderService] setPriceCalculator called");
    }

    public void placeOrder(String productName, double price, int quantity) {
        double unitPrice = priceCalculator.calculate(price);
        double totalPrice = unitPrice * quantity;
        System.out.println("    [OrderService] Placing order: " + quantity + "x " + productName +
                         " = $" + totalPrice);
        productRepository.save(productName, unitPrice);
    }
}
