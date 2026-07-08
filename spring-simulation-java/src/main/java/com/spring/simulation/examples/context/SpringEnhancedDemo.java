package com.spring.simulation.examples.context;

import com.spring.simulation.aop.PointcutExpressionParser;
import com.spring.simulation.ioc.Bean;
import com.spring.simulation.ioc.BeanDefinition;
import com.spring.simulation.ioc.BeanFactory;
import com.spring.simulation.ioc.BeanScope;
import com.spring.simulation.ioc.Configuration;
import com.spring.simulation.spel.SpelExpressionParser;
import com.spring.simulation.transaction.Propagation;
import com.spring.simulation.transaction.Transactional;

/**
 * 演示所有新增功能：
 * 1. CGLIB 代理
 * 2. 完整的作用域支持（singleton, prototype）
 * 3. @Configuration 和 @Bean 注解
 * 4. SpEL 表达式解析
 * 5. 完整的事务传播机制
 * 6. Aspect 切点表达式
 */
public class SpringEnhancedDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("========================================");
        System.out.println("Spring Enhanced Features Demo");
        System.out.println("========================================\n");

        // 1. 演示 SpEL 表达式
        demonstrateSpEL();

        // 2. 演示切点表达式
        demonstratePointcutExpression();

        // 3. 演示作用域
        demonstrateScopes();

        // 4. 演示事务传播
        demonstrateTransactionPropagation();

        // 5. 演示 @Configuration 和 @Bean
        demonstrateConfiguration();
    }

    /**
     * 演示 SpEL 表达式
     */
    private static void demonstrateSpEL() {
        System.out.println("\n========== 1. SpEL Expression Demo ==========");

        SpelExpressionParser parser = new SpelExpressionParser();

        // 字面量
        System.out.println("Literal: " + parser.parseExpression("'Hello World'"));
        System.out.println("Number: " + parser.parseExpression("123"));
        System.out.println("Boolean: " + parser.parseExpression("true"));

        // 属性占位符
        System.out.println("System Property: " + parser.parseExpression("${user.name}"));
        System.out.println("System Property: " + parser.parseExpression("${java.version}"));

        // SpEL 表达式
        System.out.println("SpEL - System Property: " + parser.parseExpression("#{systemProperties['user.name']}"));
        System.out.println("SpEL - Arithmetic: " + parser.parseExpression("#{10 + 20}"));

        // 上下文变量
        parser.setVariable("appName", "Spring Simulation");
        System.out.println("Context Variable: " + parser.parseExpression("#{appName}"));
    }

    /**
     * 演示切点表达式
     */
    private static void demonstratePointcutExpression() throws Exception {
        System.out.println("\n========== 2. Pointcut Expression Demo ==========");

        PointcutExpressionParser pointcutParser = new PointcutExpressionParser();

        // 测试类和方法
        Class<?> testClass = TestService.class;
        java.lang.reflect.Method saveMethod = testClass.getMethod("saveUser", String.class);
        java.lang.reflect.Method findMethod = testClass.getMethod("findUser", String.class);

        // execution 表达式
        System.out.println("\nTesting execution expressions:");
        System.out.println("execution(* save*(..)) matches saveUser: " +
                pointcutParser.matches("execution(* save*(..))", saveMethod, testClass));
        System.out.println("execution(* save*(..)) matches findUser: " +
                pointcutParser.matches("execution(* save*(..))", findMethod, testClass));
        System.out.println("execution(* *(..)) matches saveUser: " +
                pointcutParser.matches("execution(* *(..))", saveMethod, testClass));

        // within 表达式
        System.out.println("\nTesting within expressions:");
        System.out.println("within(com.spring.simulation.examples.context.*) matches TestService: " +
                pointcutParser.matches("within(com.spring.simulation.examples.context.*)", saveMethod, testClass));

        // @annotation 表达式
        System.out.println("\nTesting @annotation expressions:");
        System.out.println("@annotation(Transactional) matches saveUser: " +
                pointcutParser.matches("@annotation(Transactional)", saveMethod, testClass));
    }

    /**
     * 演示作用域
     */
    private static void demonstrateScopes() throws Exception {
        System.out.println("\n========== 3. Bean Scope Demo ==========");

        // 创建 BeanFactory
        BeanFactory beanFactory = new BeanFactory();

        // 注册 singleton Bean
        BeanDefinition singletonDef = new BeanDefinition("singletonBean", SingletonBean.class);
        singletonDef.setScope(BeanScope.SINGLETON);
        beanFactory.registerBeanDefinition("singletonBean", singletonDef);

        // 注册 prototype Bean
        BeanDefinition prototypeDef = new BeanDefinition("prototypeBean", PrototypeBean.class);
        prototypeDef.setScope(BeanScope.PROTOTYPE);
        beanFactory.registerBeanDefinition("prototypeBean", prototypeDef);

        // 测试 singleton
        System.out.println("\nTesting Singleton Scope:");
        Object singleton1 = beanFactory.getBean("singletonBean");
        Object singleton2 = beanFactory.getBean("singletonBean");
        System.out.println("Same instance: " + (singleton1 == singleton2));

        // 测试 prototype
        System.out.println("\nTesting Prototype Scope:");
        Object prototype1 = beanFactory.getBean("prototypeBean");
        Object prototype2 = beanFactory.getBean("prototypeBean");
        System.out.println("Different instances: " + (prototype1 != prototype2));
    }

    /**
     * 演示事务传播
     */
    private static void demonstrateTransactionPropagation() {
        System.out.println("\n========== 4. Transaction Propagation Demo ==========");

        com.spring.simulation.transaction.TransactionManager txManager = new com.spring.simulation.transaction.TransactionManager();

        // REQUIRED
        System.out.println("\nTesting REQUIRED:");
        com.spring.simulation.transaction.TransactionManager.TransactionStatus tx1 = txManager
                .begin(Propagation.REQUIRED);
        System.out.println("Transaction active: " + (tx1 != null && tx1.isActive()));
        txManager.commit(tx1);

        // REQUIRES_NEW
        System.out.println("\nTesting REQUIRES_NEW:");
        com.spring.simulation.transaction.TransactionManager.TransactionStatus tx2 = txManager
                .begin(Propagation.REQUIRED);
        com.spring.simulation.transaction.TransactionManager.TransactionStatus tx3 = txManager
                .begin(Propagation.REQUIRES_NEW);
        System.out.println("New transaction created: " + (tx3 != null && tx3.isNewTransaction()));
        txManager.commit(tx3);
        txManager.commit(tx2);

        // NESTED
        System.out.println("\nTesting NESTED:");
        com.spring.simulation.transaction.TransactionManager.TransactionStatus tx4 = txManager
                .begin(Propagation.REQUIRED);
        com.spring.simulation.transaction.TransactionManager.TransactionStatus tx5 = txManager
                .begin(Propagation.NESTED);
        System.out.println("Nested transaction: " + (tx5 != null && tx5.isNested()));
        txManager.commit(tx5);
        txManager.commit(tx4);
    }

    /**
     * 演示 @Configuration 和 @Bean
     */
    private static void demonstrateConfiguration() {
        System.out.println("\n========== 5. @Configuration and @Bean Demo ==========");
        System.out.println("See AppConfig class for @Configuration example");
        System.out.println("@Bean methods define beans programmatically");
        System.out.println("Supports custom initialization logic and third-party library integration");
    }

    // 测试类
    public static class TestService {
        @Transactional
        public void saveUser(String name) {
            System.out.println("Saving user: " + name);
        }

        public void findUser(String name) {
            System.out.println("Finding user: " + name);
        }
    }

    // Singleton Bean
    public static class SingletonBean {
        public SingletonBean() {
            System.out.println("   Creating SingletonBean instance");
        }
    }

    // Prototype Bean
    public static class PrototypeBean {
        public PrototypeBean() {
            System.out.println("   Creating PrototypeBean instance");
        }
    }

    // 配置类示例
    @Configuration
    public static class AppConfig {

        @Bean
        public DataSource dataSource() {
            return new DataSource("jdbc:mysql://localhost:3306/test");
        }

        @Bean(scope = BeanScope.PROTOTYPE)
        public Connection connection() {
            return new Connection();
        }
    }

    // 示例类
    public static class DataSource {
        private String url;

        public DataSource(String url) {
            this.url = url;
        }

        public String getUrl() {
            return url;
        }
    }

    public static class Connection {
        public Connection() {
            System.out.println("Creating new connection");
        }
    }
}
