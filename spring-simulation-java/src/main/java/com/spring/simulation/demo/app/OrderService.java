package com.spring.simulation.demo.app;

import com.spring.simulation.ioc.annotation.Autowired;
import com.spring.simulation.ioc.annotation.Component;
import com.spring.simulation.ioc.aware.BeanNameAware;
import com.spring.simulation.ioc.lifecycle.DisposableBean;
import com.spring.simulation.ioc.lifecycle.InitializingBean;
import com.spring.simulation.transaction.Transactional;

@Component
public class OrderService implements OrderOperations, InitializingBean, DisposableBean, BeanNameAware {
    @Autowired
    private UserRepository userRepository;

    private String beanName;

    @Override
    @Transactional(propagation = "REQUIRED", isolation = "READ_COMMITTED")
    public void createOrder(String username, String product) {
        System.out.println("    [OrderService] Creating order for " + username + ": " + product);
        userRepository.save(username);
        System.out.println("    [OrderService] Order created successfully");
    }

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
        System.out.println("   [BeanNameAware] Bean name set to: " + name);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        System.out.println("   [InitializingBean] OrderService initialized, bean name: " + beanName);
    }

    @Override
    public void destroy() throws Exception {
        System.out.println("   [DisposableBean] OrderService destroyed, cleaning up resources");
    }
}
