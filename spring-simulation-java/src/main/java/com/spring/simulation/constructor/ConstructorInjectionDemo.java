package com.spring.simulation.constructor;

import com.spring.simulation.ioc.context.AnnotationConfigApplicationContext;

public class ConstructorInjectionDemo {
    public static void main(String[] args) throws Exception {
        System.out.println("========== Constructor Injection Demo ==========\n");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                "com.spring.simulation.constructor");
        context.refresh();

        System.out.println("\n========== Testing Constructor Injection ==========");
        ProductService productService = (ProductService) context.getBean("productService");
        productService.createProduct("Laptop", 999.99);

        System.out.println("\n========== Testing Setter Injection ==========");
        OrderService orderService = (OrderService) context.getBean("orderService");
        orderService.placeOrder("Mouse", 29.99, 3);

        System.out.println("\n========== Testing Aware Interfaces ==========");
        AwareDemo awareDemo = (AwareDemo) context.getBean("awareDemo");
        awareDemo.logWithBeanName("Demonstrating BeanNameAware");
        awareDemo.dynamicGetBean();
        awareDemo.accessEnvironment();

        context.close();
    }
}
