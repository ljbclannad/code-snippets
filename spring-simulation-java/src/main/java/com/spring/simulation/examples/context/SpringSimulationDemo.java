package com.spring.simulation.examples.context;

import com.spring.simulation.aop.AfterAdvice;
import com.spring.simulation.aop.AroundAdvice;
import com.spring.simulation.aop.BeforeAdvice;
import com.spring.simulation.aop.JdkDynamicAopProxy;
import com.spring.simulation.demo.app.UserRepository;
import com.spring.simulation.demo.app.UserRepositoryImpl;
import com.spring.simulation.demo.app.UserService;
import com.spring.simulation.demo.app.UserServiceImpl;
import com.spring.simulation.demo.extension.CustomBeanFactoryPostProcessor;
import com.spring.simulation.ioc.ApplicationContext;

public class SpringSimulationDemo {
    public static void main(String[] args) throws Exception {
        ApplicationContext context = new ApplicationContext();

        context.addBeanFactoryPostProcessor(new CustomBeanFactoryPostProcessor());

        System.out.println("========== Registering Bean Definitions ==========");
        // 往beanFactory中注册BeanDefinition
        context.registerBeanDefinition("userRepository", UserRepositoryImpl.class);
        context.registerBeanDefinition("userService", UserServiceImpl.class);

        context.refresh();

        System.out.println("\n========== Testing IoC Container ==========");
        UserService userService = (UserService) context.getBean("userService");
        System.out.println("Got bean: " + userService.getClass().getName());

        System.out.println("\n========== Testing AOP Proxy ==========");
        UserRepository userRepository = (UserRepository) context.getBean("userRepository");

        JdkDynamicAopProxy aopProxy = new JdkDynamicAopProxy(userRepository);
        /*
         * BeforeAdvice 进入 → before 逻辑 → proceed()
         * AroundAdvice 进入 → before 逻辑(第1次) → proceed()
         * AfterAdvice 进入 → proceed()
         * 目标方法执行
         * AfterAdvice → after 逻辑 → 退出
         * AroundAdvice → after 逻辑(第2次) → 退出
         * BeforeAdvice 退出
         */
        aopProxy.addInterceptor(new BeforeAdvice("LoggingAspect"));
        aopProxy.addInterceptor(new AroundAdvice("PerformanceAspect"));
        aopProxy.addInterceptor(new AfterAdvice("CleanupAspect"));

        UserRepository proxiedRepository = (UserRepository) aopProxy.getProxy();

        System.out.println("\n========== Executing Business Logic with AOP ==========");
        System.out.println("\n--- Call 1: save() ---");
        proxiedRepository.save("Alice");

        System.out.println("\n--- Call 2: findByName() ---");
        String result = proxiedRepository.findByName("Alice");
        System.out.println("Result: " + result);

        System.out.println("\n========== Testing IoC + AOP Integration ==========");
        UserServiceImpl serviceImpl = (UserServiceImpl) context.getBean("userService");
        JdkDynamicAopProxy serviceProxy = new JdkDynamicAopProxy(serviceImpl);
        serviceProxy.addInterceptor(new BeforeAdvice("TransactionAspect"));
        serviceProxy.addInterceptor(new AroundAdvice("SecurityAspect"));

        UserService proxiedService = (UserService) serviceProxy.getProxy();

        System.out.println("\n--- Call 3: createUser() with AOP ---");
        proxiedService.createUser("Bob");

        System.out.println("\n--- Call 4: getUserInfo() with AOP ---");
        String userInfo = proxiedService.getUserInfo("Bob");
        System.out.println("User Info: " + userInfo);

        System.out.println("\n========== Demo Completed ==========");
    }
}
