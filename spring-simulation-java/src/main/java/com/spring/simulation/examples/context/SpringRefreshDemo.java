package com.spring.simulation.examples.context;

import com.spring.simulation.aop.AfterAdvice;
import com.spring.simulation.aop.AroundAdvice;
import com.spring.simulation.aop.BeforeAdvice;
import com.spring.simulation.aop.JdkDynamicAopProxy;
import com.spring.simulation.demo.app.UserRepository;
import com.spring.simulation.demo.app.UserService;
import com.spring.simulation.ioc.AnnotationConfigWebApplicationContext;

/**
 * 主线容器 Demo。
 *
 * 这个入口承担项目里最重要的学习主线：
 * 1. refresh() 12 阶段
 * 2. 组件扫描与 BeanDefinition 注册
 * 3. 依赖注入与 Bean 获取
 * 4. 在业务 Bean 上叠加最小 AOP 示例
 *
 * 后续 IoC 容器深化优先从这里扩展，而不是再新增新的基础 Demo。
 */
public class SpringRefreshDemo {
    public static void main(String[] args) throws Exception {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║   Spring ApplicationContext.refresh() 流程演示             ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");

        AnnotationConfigWebApplicationContext context =
            new AnnotationConfigWebApplicationContext("com.spring.simulation.demo");

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
