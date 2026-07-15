package com.spring.simulation.constructor;

import com.spring.simulation.environment.Environment;
import com.spring.simulation.ioc.annotation.Component;
import com.spring.simulation.ioc.aware.ApplicationContextAware;
import com.spring.simulation.ioc.aware.BeanFactoryAware;
import com.spring.simulation.ioc.aware.BeanNameAware;
import com.spring.simulation.ioc.context.ApplicationContext;
import com.spring.simulation.ioc.factory.BeanFactory;

/**
 * Aware 接口使用场景演示
 *
 * 什么时候需要实现 Aware 接口？
 * 1. 需要知道自己在容器中的名称 -> BeanNameAware
 * 2. 需要手动从容器获取其他 Bean -> BeanFactoryAware
 * 3. 需要发布事件或访问环境变量 -> ApplicationContextAware
 */
@Component
public class AwareDemo implements BeanNameAware, BeanFactoryAware, ApplicationContextAware {

    private String beanName;
    private BeanFactory beanFactory;
    private ApplicationContext applicationContext;

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
        System.out.println("    [AwareDemo] BeanNameAware.setBeanName() called: " + name);
        System.out.println("      使用场景：需要在日志中记录 Bean 名称，或根据名称做不同处理");
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
        System.out.println("    [AwareDemo] BeanFactoryAware.setBeanFactory() called");
        System.out.println("      使用场景：需要手动从容器获取 Bean（动态获取、延迟加载）");
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        System.out.println("    [AwareDemo] ApplicationContextAware.setApplicationContext() called");
        System.out.println("      使用场景：需要发布事件、访问环境变量、获取资源文件");
    }

    /**
     * 场景 1：根据 Bean 名称做不同处理
     */
    public void logWithBeanName(String message) {
        System.out.println("    [" + beanName + "] " + message);
    }

    /**
     * 场景 2：动态获取 Bean（避免循环依赖、延迟加载）
     */
    public void dynamicGetBean() throws Exception {
        // 不通过 @Autowired 注入，而是需要时才获取
        ProductRepository repo = (ProductRepository) beanFactory.getBean("productRepository");
        System.out.println("    [AwareDemo] Dynamically got bean: " + repo.getClass().getSimpleName());
        System.out.println("      使用场景：避免循环依赖、按需加载、获取 prototype 作用域的 Bean");
    }

    /**
     * 场景 3：访问环境变量
     */
    public void accessEnvironment() throws Exception {
        Environment env = (Environment) applicationContext.getBean("environment");
        System.out.println("    [AwareDemo] Accessed environment: " + env);
        System.out.println("      使用场景：读取配置文件、系统属性、环境变量");
    }
}
