package com.spring.simulation.demo.guide;

/**
 * 真实 Spring 的 Bean 注册机制演示
 *
 * 1. 自动扫描注册（最常用）
 * 2. Java 配置注册
 * 3. 手动注册（编程式）
 */
public class BeanRegistrationDemo {

    /**
     * 方式 1: 自动扫描注册
     *
     * Spring 通过 @ComponentScan 扫描指定包下的所有类
     * 找到带有 @Component、@Service、@Repository、@Controller 注解的类
     * 自动注册为 Bean
     */
    public static void demonstrateAutoScan() {
        System.out.println("========== 1. 自动扫描注册 ==========\n");

        System.out.println("配置类：");
        System.out.println("@Configuration");
        System.out.println("@ComponentScan(basePackages = \"com.example\")");
        System.out.println("public class AppConfig { }\n");

        System.out.println("Bean 类：");
        System.out.println("@Service  // 默认 Bean 名称：userServiceImpl");
        System.out.println("public class UserServiceImpl implements UserService { }\n");

        System.out.println("@Repository(\"userRepo\")  // 自定义 Bean 名称：userRepo");
        System.out.println("public class UserRepositoryImpl implements UserRepository { }\n");

        System.out.println("Bean 名称生成规则：");
        System.out.println("1. 默认：类名首字母小写");
        System.out.println("   UserServiceImpl → userServiceImpl");
        System.out.println("   OrderController → orderController");
        System.out.println("2. 自定义：通过注解的 value 属性");
        System.out.println("   @Service(\"myService\") → myService");
        System.out.println("   @Repository(\"userRepo\") → userRepo\n");

        System.out.println("扫描过程：");
        System.out.println("1. ComponentScan 指定扫描的包路径");
        System.out.println("2. ClassPathBeanDefinitionScanner 扫描包下的所有 .class 文件");
        System.out.println("3. 检查类是否有 @Component 及其派生注解");
        System.out.println("4. 生成 BeanDefinition 并注册到 BeanFactory");
        System.out.println("5. Bean 名称通过 BeanNameGenerator 生成\n");
    }

    /**
     * 方式 2: Java 配置注册
     *
     * 通过 @Configuration 类中的 @Bean 方法注册
     * 适用于第三方库的 Bean 或需要复杂初始化的 Bean
     */
    public static void demonstrateJavaConfig() {
        System.out.println("========== 2. Java 配置注册 ==========\n");

        System.out.println("配置类：");
        System.out.println("@Configuration");
        System.out.println("public class DataSourceConfig {");
        System.out.println("    ");
        System.out.println("    @Bean  // 默认 Bean 名称：dataSource（方法名）");
        System.out.println("    public DataSource dataSource() {");
        System.out.println("        return new HikariDataSource();");
        System.out.println("    }");
        System.out.println("    ");
        System.out.println("    @Bean(\"myCache\")  // 自定义 Bean 名称：myCache");
        System.out.println("    public CacheManager cacheManager() {");
        System.out.println("        return new ConcurrentMapCacheManager();");
        System.out.println("    }");
        System.out.println("}\n");

        System.out.println("Bean 名称生成规则：");
        System.out.println("1. 默认：方法名");
        System.out.println("   public DataSource dataSource() → dataSource");
        System.out.println("   public UserService userService() → userService");
        System.out.println("2. 自定义：通过 @Bean 的 name 或 value 属性");
        System.out.println("   @Bean(\"myCache\") → myCache");
        System.out.println("   @Bean(name = {\"ds\", \"dataSource\"}) → ds 和 dataSource（别名）\n");

        System.out.println("使用场景：");
        System.out.println("1. 第三方库的 Bean（无法添加 @Component）");
        System.out.println("2. 需要复杂初始化逻辑的 Bean");
        System.out.println("3. 条件化创建 Bean（@Conditional）");
        System.out.println("4. 需要编程式配置的 Bean\n");
    }

    /**
     * 方式 3: 手动注册（编程式）
     *
     * 直接调用 BeanFactory 的 API 注册
     * 适用于动态注册、测试场景
     */
    public static void demonstrateManualRegistration() {
        System.out.println("========== 3. 手动注册（编程式）==========\n");

        System.out.println("代码示例：");
        System.out.println("// 创建 BeanDefinition");
        System.out
                .println("BeanDefinition beanDefinition = new BeanDefinition(\"userService\", UserServiceImpl.class);");
        System.out.println("beanDefinition.setScope(BeanScope.SINGLETON);");
        System.out.println("");
        System.out.println("// 注册到 BeanFactory");
        System.out.println("beanFactory.registerBeanDefinition(\"userService\", beanDefinition);\n");

        System.out.println("Bean 名称选择：");
        System.out.println("1. 完全由开发者决定");
        System.out.println("2. 通常遵循驼峰命名规则");
        System.out.println("3. 建议使用接口名首字母小写（如果有接口）");
        System.out.println("   UserService userService = ... → \"userService\"");
        System.out.println("4. 或使用实现类名首字母小写");
        System.out.println("   UserServiceImpl → \"userServiceImpl\"\n");

        System.out.println("使用场景：");
        System.out.println("1. 单元测试中动态注册 Mock 对象");
        System.out.println("2. 框架内部动态注册 Bean");
        System.out.println("3. 需要在运行时决定注册哪些 Bean");
        System.out.println("4. 教学演示（如本项目）\n");
    }

    /**
     * Bean 名称冲突处理
     */
    public static void demonstrateNameConflict() {
        System.out.println("========== 4. Bean 名称冲突处理 ==========\n");

        System.out.println("场景 1: 同一个类在不同包下");
        System.out.println("com.example.service.UserService");
        System.out.println("com.example.admin.UserService");
        System.out.println("解决方案：");
        System.out.println("1. 使用全限定类名作为 Bean 名称");
        System.out.println("2. 使用自定义名称：@Service(\"adminUserService\")\n");

        System.out.println("场景 2: 同一个接口有多个实现");
        System.out.println("@Service(\"mysqlUserRepo\")");
        System.out.println("public class MysqlUserRepository implements UserRepository { }");
        System.out.println("");
        System.out.println("@Service(\"mongoUserRepo\")");
        System.out.println("public class MongoUserRepository implements UserRepository { }\n");

        System.out.println("注入时指定 Bean 名称：");
        System.out.println("@Autowired");
        System.out.println("@Qualifier(\"mysqlUserRepo\")");
        System.out.println("private UserRepository userRepository;\n");

        System.out.println("场景 3: @Bean 方法名冲突");
        System.out.println("Spring 会抛出异常：BeanDefinitionOverrideException");
        System.out.println("解决方案：");
        System.out.println("1. 修改方法名");
        System.out.println("2. 使用 @Bean(name = \"uniqueName\")");
        System.out.println("3. 配置允许覆盖：spring.main.allow-bean-definition-overriding=true\n");
    }

    /**
     * 真实项目中的最佳实践
     */
    public static void demonstrateBestPractices() {
        System.out.println("========== 5. 真实项目最佳实践 ==========\n");

        System.out.println("1. 优先使用自动扫描");
        System.out.println("   - 大部分业务 Bean 使用 @Component/@Service/@Repository");
        System.out.println("   - 简单、清晰、易维护\n");

        System.out.println("2. Bean 名称规范");
        System.out.println("   - 使用默认名称（类名首字母小写）");
        System.out.println("   - 只在有冲突或特殊需求时自定义");
        System.out.println("   - 保持命名一致性\n");

        System.out.println("3. 分层命名");
        System.out.println("   Controller 层：XxxController");
        System.out.println("   Service 层：XxxService / XxxServiceImpl");
        System.out.println("   Repository 层：XxxRepository / XxxRepositoryImpl");
        System.out.println("   Component 层：XxxComponent / XxxHelper\n");

        System.out.println("4. 接口与实现");
        System.out.println("   - 如果有接口，注解加在实现类上");
        System.out.println("   - Bean 名称使用接口名首字母小写");
        System.out.println("   - 依赖注入时使用接口类型\n");

        System.out.println("5. 第三方库 Bean");
        System.out.println("   - 使用 @Configuration + @Bean");
        System.out.println("   - 集中在配置类中管理");
        System.out.println("   - 例如：DataSourceConfig, RedisConfig, KafkaConfig\n");

        System.out.println("6. 条件化注册");
        System.out.println("   @ConditionalOnProperty(name = \"feature.enabled\", havingValue = \"true\")");
        System.out.println("   @ConditionalOnClass(name = \"com.example.SomeClass\")");
        System.out.println("   @ConditionalOnMissingBean(DataSource.class)\n");
    }

    /**
     * Spring Boot 的自动配置
     */
    public static void demonstrateAutoConfiguration() {
        System.out.println("========== 6. Spring Boot 自动配置 ==========\n");

        System.out.println("Spring Boot 通过 @EnableAutoConfiguration 自动注册 Bean：");
        System.out.println("");
        System.out.println("1. 扫描 META-INF/spring.factories");
        System.out.println("2. 加载所有 AutoConfiguration 类");
        System.out.println("3. 根据条件注解决定是否注册");
        System.out.println("4. 自动配置 DataSource、JPA、Redis 等\n");

        System.out.println("示例：DataSourceAutoConfiguration");
        System.out.println("@Configuration");
        System.out.println("@ConditionalOnClass(DataSource.class)");
        System.out.println("@EnableConfigurationProperties(DataSourceProperties.class)");
        System.out.println("public class DataSourceAutoConfiguration {");
        System.out.println("    ");
        System.out.println("    @Bean");
        System.out.println("    @ConditionalOnMissingBean");
        System.out.println("    public DataSource dataSource(DataSourceProperties properties) {");
        System.out.println("        return DataSourceBuilder.create()");
        System.out.println("            .url(properties.getUrl())");
        System.out.println("            .username(properties.getUsername())");
        System.out.println("            .password(properties.getPassword())");
        System.out.println("            .build();");
        System.out.println("    }");
        System.out.println("}\n");

        System.out.println("Bean 名称：");
        System.out.println("- 自动配置的 Bean 通常使用方法名");
        System.out.println("- 用户可以通过自定义 Bean 覆盖（@ConditionalOnMissingBean）\n");
    }

    public static void main(String[] args) {
        demonstrateAutoScan();
        demonstrateJavaConfig();
        demonstrateManualRegistration();
        demonstrateNameConflict();
        demonstrateBestPractices();
        demonstrateAutoConfiguration();

        System.out.println("========================================");
        System.out.println("总结：");
        System.out.println("1. 自动扫描是主流方式（@Component 系列注解）");
        System.out.println("2. Bean 名称默认为类名首字母小写");
        System.out.println("3. 可以通过注解的 value 属性自定义名称");
        System.out.println("4. 手动注册主要用于测试和特殊场景");
        System.out.println("5. Spring Boot 通过自动配置简化了大量配置");
        System.out.println("========================================");
    }
}
