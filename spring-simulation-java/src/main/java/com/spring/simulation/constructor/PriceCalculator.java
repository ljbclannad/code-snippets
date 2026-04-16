package com.spring.simulation.constructor;

import com.spring.simulation.ioc.Component;

@Component
public class PriceCalculator {
    public PriceCalculator() {
        System.out.println("    [PriceCalculator] Constructor called (no-arg)");
    }

    public double calculate(double basePrice) {
        double tax = basePrice * 0.1;
        System.out.println("    [PriceCalculator] Calculated price with tax: $" + (basePrice + tax));
        return basePrice + tax;
    }
}
