package com.spring.simulation.ioc;

/**
 * 更贴近 Spring Web 场景的注解配置上下文。
 *
 * 关系上可以理解为：
 * ApplicationContext
 *   -> AnnotationConfigApplicationContext
 *   -> AnnotationConfigWebApplicationContext
 *
 * Web 版本在通用注解配置能力之上，额外接管：
 * 1. Web 环境判定
 * 2. BeanFactory 的 Web 扩展处理
 * 3. refresh 过程中的 Web 容器启动阶段
 */
public class AnnotationConfigWebApplicationContext extends AnnotationConfigApplicationContext {

    public AnnotationConfigWebApplicationContext(String basePackage) {
        super(basePackage);
    }

    @Override
    protected boolean isWebEnvironment() {
        return true;
    }

    @Override
    protected void onRefresh() {
        System.out.println("\n[Phase 9] onRefresh()");
        System.out.println("  - [Web Container] Initializing embedded web server");
        System.out.println("    * Creating DispatcherServlet");
        System.out.println("    * Registering servlet mappings");
        System.out.println("    * Starting embedded Tomcat/Jetty/Undertow (simulated)");
    }
}
