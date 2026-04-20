package com.spring.simulation;

import com.spring.simulation.demo.AutowiredAnnotationBeanPostProcessor;
import com.spring.simulation.ioc.ApplicationContext;
import com.spring.simulation.ioc.Autowired;
import com.spring.simulation.ioc.BeanDefinition;

/**
 * @Autowired 注入演示
 *
 * 演示 Spring 如何在 refresh() 流程中处理 @Autowired 注解：
 * 1. 阶段 6: 注册 AutowiredAnnotationBeanPostProcessor
 * 2. 阶段 11: 实例化 Bean 时，通过 BeanPostProcessor 完成依赖注入
 */
public class AutowiredDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("========== @Autowired Injection Demo ==========\n");

        // 创建 ApplicationContext
        ApplicationContext context = new ApplicationContext();

        // 注册 Bean 定义
        context.registerBeanDefinition("userRepository", UserRepository.class);
        context.registerBeanDefinition("userService", UserService.class);
        context.registerBeanDefinition("orderService", OrderService.class);

        // 注册 AutowiredAnnotationBeanPostProcessor（模拟 Spring 自动注册）
        context.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(context.getBeanFactory()));

        // 执行 refresh（触发完整的容器初始化流程）
        context.refresh();

        // 获取 Bean 并验证依赖注入
        System.out.println("\n========== Verifying Dependency Injection ==========");
        UserService userService = (UserService) context.getBean("userService");
        userService.createUser("Alice");

        OrderService orderService = (OrderService) context.getBean("orderService");
        orderService.createOrder("Alice", "Laptop");

        context.close();
    }

    // ========== 示例 Bean 类 ==========

    /**
     * Repository 层：数据访问
     */
    public static class UserRepository {
        public void save(String username) {
            System.out.println("  [UserRepository] Saving user: " + username);
        }

        public String findById(String id) {
            return "User-" + id;
        }
    }

    /**
     * Service 层：业务逻辑
     * 演示字段注入（Field Injection）
     */
    public static class UserService {
        // 通过 @Autowired 注入依赖（字段注入）
        @Autowired
        private UserRepository userRepository;

        public void createUser(String username) {
            System.out.println("\n[UserService] Creating user: " + username);
            if (userRepository == null) {
                System.out.println("  ✗ userRepository is NULL - injection failed!");
            } else {
                System.out.println("  ✓ userRepository injected successfully");
                userRepository.save(username);
            }
        }
    }

    /**
     * Service 层：订单服务
     * 演示方法注入（Setter Injection）
     */
    public static class OrderService {
        private UserRepository userRepository;
        private UserService userService;

        // 通过 @Autowired 注入依赖（方法注入）
        @Autowired
        public void setDependencies(UserRepository userRepository, UserService userService) {
            System.out.println("     [OrderService] Setter injection called");
            this.userRepository = userRepository;
            this.userService = userService;
        }

        public void createOrder(String username, String product) {
            System.out.println("\n[OrderService] Creating order for: " + username + ", product: " + product);
            if (userRepository == null || userService == null) {
                System.out.println("  ✗ Dependencies are NULL - injection failed!");
            } else {
                System.out.println("  ✓ Dependencies injected successfully");
                System.out.println("  - userRepository: " + userRepository.getClass().getSimpleName());
                System.out.println("  - userService: " + userService.getClass().getSimpleName());
            }
        }
    }
}
