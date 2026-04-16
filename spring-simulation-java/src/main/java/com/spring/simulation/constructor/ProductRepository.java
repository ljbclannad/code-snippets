package com.spring.simulation.constructor;

import com.spring.simulation.ioc.Component;

@Component
public class ProductRepository {
    public ProductRepository() {
        System.out.println("    [ProductRepository] Constructor called (no-arg)");
    }

    public void save(String name, double price) {
        System.out.println("    [ProductRepository] Saved product: " + name + " ($" + price + ")");
    }
}
