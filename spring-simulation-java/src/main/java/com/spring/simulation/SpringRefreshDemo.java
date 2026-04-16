package com.spring.simulation;

import com.spring.simulation.aop.*;
import com.spring.simulation.demo.*;
import com.spring.simulation.ioc.AnnotationConfigApplicationContext;

public class SpringRefreshDemo {
    public static void main(String[] args) throws Exception {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║   Spring ApplicationContext.refresh() 流程演示             ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");

        AnnotationConfigApplicationContext context =
            new AnnotationConfigApplicationContext("com.spring.simulation.demo");

        context.refresh();

        System.out.println("\n========== Testing Bean Retrieval ==========");
        UserService userService = (UserService) context.getBean("userServiceImpl");
        UserRepository userRepository = (UserRepository) context.getBean("userRepositoryImpl");

        System.out.println("\n========== Testing Business Logic ==========");
        userService.createUser("Alice");
        String info = userService.getUserInfo("Alice");
        System.out.println("Retrieved: " + info);

        System.out.println("\n========== Testing AOP Integration ==========");
        JdkDynamicAopProxy aopProxy = new JdkDynamicAopProxy(userRepository);
        aopProxy.addInterceptor(new BeforeAdvice("TransactionAspect"));
        aopProxy.addInterceptor(new AroundAdvice("PerformanceAspect"));
        aopProxy.addInterceptor(new AfterAdvice("LoggingAspect"));

        UserRepository proxiedRepository = (UserRepository) aopProxy.getProxy();
        proxiedRepository.save("Bob");

        System.out.println("\n========== Demo Completed ==========");
    }
}
