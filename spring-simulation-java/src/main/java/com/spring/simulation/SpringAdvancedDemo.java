package com.spring.simulation;

import com.spring.simulation.aop.AroundAdvice;
import com.spring.simulation.aop.JdkDynamicAopProxy;
import com.spring.simulation.circular.ServiceA;
import com.spring.simulation.circular.ServiceB;
import com.spring.simulation.demo.CustomBeanFactoryPostProcessor;
import com.spring.simulation.demo.LoggingBeanPostProcessor;
import com.spring.simulation.demo.OrderService;
import com.spring.simulation.demo.StartupListener;
import com.spring.simulation.demo.UserRepositoryImpl;
import com.spring.simulation.event.ApplicationEventMulticaster;
import com.spring.simulation.event.ContextClosedEvent;
import com.spring.simulation.event.ContextRefreshedEvent;
import com.spring.simulation.ioc.ApplicationContext;
import com.spring.simulation.transaction.TransactionInterceptor;

public class SpringAdvancedDemo {
    public static void main(String[] args) throws Exception {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║        Spring 框架底层核心机制完整演示                      ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");

        ApplicationContext context = new ApplicationContext();

        System.out.println("\n========== 1. Bean 生命周期演示 ==========");
        context.addBeanPostProcessor(new LoggingBeanPostProcessor());
        context.addBeanFactoryPostProcessor(new CustomBeanFactoryPostProcessor());

        context.registerBeanDefinition("userRepository", UserRepositoryImpl.class);
        context.registerBeanDefinition("orderService", OrderService.class);

        context.refresh();

        System.out.println("\n========== 2. 循环依赖解决演示 ==========");
        ApplicationContext circularContext = new ApplicationContext();
        circularContext.registerBeanDefinition("serviceA", ServiceA.class);
        circularContext.registerBeanDefinition("serviceB", ServiceB.class);
        circularContext.refresh();

        ServiceA serviceA = (ServiceA) circularContext.getBean("serviceA");
        System.out.println("\n[Test] Testing circular dependency:");
        serviceA.doSomething();

        System.out.println("\n========== 3. 事务管理演示 ==========");
        OrderService orderService = (OrderService) context.getBean("orderService");

        JdkDynamicAopProxy txProxy = new JdkDynamicAopProxy(orderService);
        txProxy.addInterceptor(new TransactionInterceptor());
        txProxy.addInterceptor(new AroundAdvice("PerformanceMonitor"));

        OrderService proxiedOrderService = (OrderService) txProxy.getProxy();
        System.out.println("\n[Test] Executing transactional method:");
        proxiedOrderService.createOrder("Alice", "Laptop");

        System.out.println("\n========== 4. 事件发布机制演示 ==========");
        ApplicationEventMulticaster eventMulticaster = new ApplicationEventMulticaster();
        eventMulticaster.addApplicationListener(new StartupListener());

        System.out.println("\n[Test] Publishing ContextRefreshedEvent:");
        eventMulticaster.multicastEvent(new ContextRefreshedEvent(context));

        System.out.println("\n[Test] Publishing ContextClosedEvent:");
        eventMulticaster.multicastEvent(new ContextClosedEvent(context));

        System.out.println("\n========== 5. Aware 接口演示 ==========");
        System.out.println("OrderService 实现了以下 Aware 接口:");
        System.out.println("  - BeanNameAware: 获取 Bean 名称");
        System.out.println("  - InitializingBean: 初始化回调");
        System.out.println("  - DisposableBean: 销毁回调");

        System.out.println("\n========== 6. 容器关闭与 Bean 销毁 ==========");
        context.close();

        System.out.println("\n========== Demo 完成 ==========");
        System.out.println("\n已演示的 Spring 核心机制:");
        System.out.println("  ✓ Bean 完整生命周期（实例化 → 属性填充 → Aware → BPP → 初始化 → 销毁）");
        System.out.println("  ✓ 循环依赖解决（三级缓存）");
        System.out.println("  ✓ BeanPostProcessor 扩展点");
        System.out.println("  ✓ BeanFactoryPostProcessor 扩展点");
        System.out.println("  ✓ Aware 接口回调");
        System.out.println("  ✓ InitializingBean 和 DisposableBean");
        System.out.println("  ✓ 事件发布与监听机制");
        System.out.println("  ✓ 声明式事务管理（@Transactional）");
        System.out.println("  ✓ AOP 动态代理与拦截器链");
    }
}
