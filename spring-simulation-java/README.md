# Spring 模拟框架 - 完整版

## 回归验证与运行边界

真实 JDBC/H2 实验：运行 `sh scripts/test-h2.sh` 验证 32 组回归（18 组传播/保存点 + 14 组回滚规则），或运行 `sh scripts/test-h2.sh --demo` 展示订单/审计结果；`sh scripts/test.sh --h2` 可一起验证。首次运行需要下载固定版本 H2，也可通过 `H2_JAR` 指定本地 JAR。详见 [H2 真实事务实验](docs/transaction/H2_TRANSACTION_EXPERIMENT.md)。

运行 `sh scripts/test.sh`：在临时目录干净编译，执行 14 组行为回归、4 组事务传播实验与 6 个主要示例，退出时删除构建产物；不依赖现有 bin。

- Context 保留 refresh 前注册的定义，每个实例只允许一次 refresh；启动失败会清理已创建单例，关闭后禁止通过 Context 获取 Bean。
- 单例创建用工厂级可重入锁串行化。字段循环依赖可以复用早期代理；构造器/原型循环依赖明确报错。初始化代码不要等待另一线程回调同一工厂。
- 自动代理共享容器级 TransactionManager。默认事务传播是状态模拟，不连接数据库；NESTED 只模拟嵌套状态，不提供真实保存点。NOT_SUPPORTED 等无事务调用也返回需完成的逻辑状态，按 begin 的逆序 commit/rollback。
- 此轮回归覆盖注册保留、配置扫描、代理身份、并发单例、事务传播、失败清理/重试、代理目标销毁、BFPP 新增定义、BPP 排序及手动单例解析。继承字段注入和跨上下文 session 隔离等先前审查边界不在本轮修复范围。


这个项目深度模拟了 Spring 框架的核心底层机制，包括 IoC 容器、AOP 代理、Bean 生命周期、循环依赖解决、事务管理等。

## 项目结构

```
spring-simulation-java/
├── docs/                            # 学习文档与规划文档
│   ├── README.md                    # 文档导航
│   ├── aop/                         # AOP 专题
│   ├── ioc/                         # IoC / 依赖注入专题
│   ├── architecture/                # 架构设计总览
│   ├── SPRING_SECONDARY_DEVELOPMENT_ROADMAP.md
│   └── SPRING_SIMULATION_TODO.md
└── src/main/java/com/spring/simulation/
    ├── examples/                      # 演示入口
    │   ├── aop/                       # AOP 执行顺序、Ordered 演示
    │   └── context/                   # 容器刷新、自动注入、综合能力演示
    ├── ioc/                           # IoC 容器核心
    │   ├── ApplicationContext.java    # 应用上下文
    │   ├── AnnotationConfigApplicationContext.java  # 注解配置上下文
    │   ├── BeanFactory.java           # Bean 工厂（含三级缓存）
    │   ├── BeanDefinition.java        # Bean 定义
    │   ├── BeanScope.java             # Bean 作用域常量
    │   ├── ComponentScanner.java      # 组件扫描器
    │   ├── BeanPostProcessor.java     # Bean 后置处理器
    │   ├── BeanFactoryPostProcessor.java  # BeanFactory 后置处理器
    │   ├── InitializingBean.java      # 初始化回调接口
    │   ├── DisposableBean.java        # 销毁回调接口
    │   ├── BeanNameAware.java         # Bean 名称感知
    │   ├── BeanFactoryAware.java      # BeanFactory 感知
    │   ├── ApplicationContextAware.java  # ApplicationContext 感知
    │   ├── @Component                 # 组件注解
    │   ├── @Autowired                 # 依赖注入注解
    │   ├── @Configuration             # 配置类注解
    │   ├── @Bean                      # Bean 定义注解
    │   └── @Scope                     # 作用域注解
    ├── aop/                           # AOP 代理机制
    │   ├── JdkDynamicAopProxy.java    # JDK 动态代理
    │   ├── CglibAopProxy.java         # CGLIB 代理
    │   ├── MethodInterceptor.java     # 方法拦截器
    │   ├── MethodInvocation.java      # 方法调用链
    │   ├── BeforeAdvice.java          # 前置通知
    │   ├── AroundAdvice.java          # 环绕通知
    │   ├── AfterAdvice.java           # 后置通知
    │   ├── @Aspect                    # 切面注解
    │   ├── @Pointcut                  # 切点注解
    │   ├── @Before                    # 前置通知注解
    │   ├── @After                     # 后置通知注解
    │   ├── @Around                    # 环绕通知注解
    │   └── PointcutExpressionParser.java  # 切点表达式解析器
    ├── transaction/                   # 事务管理
    │   ├── @Transactional             # 事务注解
    │   ├── Propagation.java           # 事务传播行为枚举
    │   ├── TransactionInterceptor.java  # 事务拦截器
    │   └── TransactionManager.java    # 事务管理器
    ├── spel/                          # SpEL 表达式
    │   ├── SpelExpressionParser.java  # SpEL 解析器
    │   └── @Value                     # 值注入注解
    ├── event/                         # 事件发布机制
    │   ├── ApplicationEvent.java      # 事件基类
    │   ├── ApplicationListener.java   # 事件监听器
    │   ├── ApplicationEventMulticaster.java  # 事件多播器
    │   ├── ContextRefreshedEvent.java
    │   └── ContextClosedEvent.java
    ├── circular/                      # 循环依赖示例
    │   ├── ServiceA.java
    │   └── ServiceB.java
    ├── demo/                          # 教学示例支撑类
    │   ├── app/                       # 示例业务 Bean
    │   ├── extension/                 # BeanFactory/BPP 扩展点示例
    │   ├── listener/                  # 事件监听器示例
    │   └── guide/                     # 讲解型示例
    └── constructor/                   # 构造器注入与 Aware 示例
```

## 核心功能

### 1. IoC 容器
- **BeanFactory**: 核心 Bean 工厂，负责 Bean 的创建和管理
- **ApplicationContext**: 应用上下文，完整的 12 阶段 refresh 流程
- **BeanDefinition**: Bean 定义，存储 Bean 的元数据
- **三级缓存**: 解决循环依赖问题
  - **一级缓存（singletonObjects）**: 存放完全初始化的单例 Bean
  - **二级缓存（earlySingletonObjects）**: 存放早期暴露的 Bean 引用（已实例化，未完成初始化）
  - **三级缓存（singletonFactories）**: 存放 Bean 工厂（ObjectFactory），用于延迟创建代理对象
- **依赖注入**: 支持 @Autowired 注解
- **组件扫描**: 支持 @Component 注解和包扫描

### 2. Bean 生命周期（完整流程）
1. **实例化** - 通过反射创建 Bean 实例
2. **属性填充** - 依赖注入（@Autowired）
3. **Aware 接口回调**
   - BeanNameAware.setBeanName()
   - BeanFactoryAware.setBeanFactory()
   - ApplicationContextAware.setApplicationContext()
4. **BeanPostProcessor 前置处理**
   - postProcessBeforeInitialization()
5. **初始化回调**
   - InitializingBean.afterPropertiesSet()
6. **BeanPostProcessor 后置处理**
   - postProcessAfterInitialization()
7. **Bean 就绪** - 可以使用
8. **销毁回调**
   - DisposableBean.destroy()

### 3. AOP 代理
- **JdkDynamicAopProxy**: 基于 JDK 动态代理的 AOP 实现
- **MethodInterceptor**: 方法拦截器接口
- **MethodInvocation**: 方法调用链（责任链模式）
- **BeforeAdvice**: 前置通知
- **AroundAdvice**: 环绕通知（包含性能监控）
- **AfterAdvice**: 后置通知
- **拦截器链**: 支持多个拦截器按顺序执行

### 4. 事务管理
- **@Transactional**: 声明式事务注解
- **TransactionInterceptor**: 事务拦截器（AOP 实现）
- **TransactionManager**: 默认事务状态模拟器
- **JdbcTransactionManager**: 真实 JDBC 实验扩展，支持 REQUIRED / REQUIRES_NEW / NESTED 的连接共享、挂起恢复、保存点及提交/回滚
- **事务传播状态模拟（不绑定数据库资源）**:
  - REQUIRED: 加入现有事务或创建新事务（默认）
  - REQUIRES_NEW: 总是创建新事务，挂起当前事务
  - NESTED: 嵌套事务，可独立回滚
  - SUPPORTS: 支持事务，但非必需
  - NOT_SUPPORTED: 不支持事务，挂起当前事务
  - MANDATORY: 必须在事务中执行
  - NEVER: 不能在事务中执行
- 支持事务隔离级别、只读等属性
- 自动提交和回滚机制；支持 `rollbackFor` / `noRollbackFor`，最具体的异常类型优先。默认受检异常不回滚，详见 [回滚规则实验](docs/transaction/ROLLBACK_RULE_EXPERIMENT.md)。

事务实验覆盖内层 `REQUIRED` 返回后外层回滚、`REQUIRES_NEW` 独立完成与挂起恢复，以及捕获内层异常后的提交差异。运行步骤和断言说明见 [事务回归实验](docs/transaction/TRANSACTION_REGRESSION.md)。

### 5. Bean 作用域
- **Singleton**: 单例（默认），容器中只有一个实例
- **Prototype**: 原型，每次获取创建新实例
- **Request**: 请求作用域（Web 环境）
- **Session**: 会话作用域（Web 环境）
- **Application**: 应用作用域（Web 环境）
- **@Scope**: 作用域注解

### 6. Java 配置
- **@Configuration**: 配置类注解
- **@Bean**: Bean 定义注解
- 支持通过方法定义 Bean
- 支持第三方库的 Bean 配置
- 支持复杂的初始化逻辑

### 7. SpEL 表达式
- **SpelExpressionParser**: 表达式解析器
- **@Value**: 值注入注解
- 支持字面量：'hello', 123, true
- 支持属性占位符：${property.name}
- 支持 SpEL 表达式：#{expression}
- 支持属性访问：systemProperties['user.name']
- 支持运算符：+, -, *, /, ==, !=, <, >, &&, ||
- 支持三元运算符：condition ? true : false

### 8. AOP 切点表达式
- **@Aspect**: 切面注解
- **@Pointcut**: 切点定义注解
- **@Before**: 前置通知注解
- **@After**: 后置通知注解
- **@Around**: 环绕通知注解
- **PointcutExpressionParser**: 切点表达式解析器
- 支持 execution 表达式：execution(* com.example.*.*(..))
- 支持 within 表达式：within(com.example.service.*)
- 支持 @annotation 表达式：@annotation(Transactional)
- 支持 @within 表达式：@within(Service)

### 9. CGLIB 代理
- **CglibAopProxy**: CGLIB 代理实现
- 支持对没有接口的类进行代理
- 通过继承目标类实现代理
- 简化实现（真实 Spring 使用 ASM 字节码生成）
- 自动提交和回滚机制；支持 `rollbackFor` / `noRollbackFor`，最具体的异常类型优先。默认受检异常不回滚，详见 [回滚规则实验](docs/transaction/ROLLBACK_RULE_EXPERIMENT.md)。

### 10. 事件发布机制
- **ApplicationEvent**: 事件基类
- **ApplicationListener**: 事件监听器接口
- **ApplicationEventMulticaster**: 事件多播器
- **ContextRefreshedEvent**: 容器刷新事件
- **ContextClosedEvent**: 容器关闭事件

### 11. 扩展点
- **BeanPostProcessor**: Bean 后置处理器（AOP 代理创建的关键）
- **BeanFactoryPostProcessor**: BeanFactory 后置处理器（修改 Bean 定义）
- **Aware 接口**: 让 Bean 感知容器内部对象

## 运行示例

### 方式 1: 增强功能演示（推荐）
```bash
cd spring-simulation-java
javac -d bin $(find src/main/java -name '*.java')
java -cp bin com.spring.simulation.examples.context.SpringEnhancedDemo
```

演示内容：
- SpEL 表达式解析
- 切点表达式匹配
- Bean 作用域（singleton vs prototype）
- 事务传播机制
- @Configuration 和 @Bean

### 方式 2: 完整的高级特性演示
```bash
java -cp bin com.spring.simulation.examples.context.SpringAdvancedDemo
```

演示内容：
- Bean 完整生命周期
- 循环依赖解决（三级缓存）
- 声明式事务管理
- 事件发布与监听
- Aware 接口回调
- BeanPostProcessor 和 BeanFactoryPostProcessor

### 方式 3: ApplicationContext.refresh() 主线演示
```bash
java -cp bin com.spring.simulation.examples.context.SpringRefreshDemo
```

演示内容：
- 完整的 12 阶段 refresh 流程
- 组件扫描和自动注册
- Bean 实例化和依赖注入
- 最小 AOP 集成示例

## 输出示例

程序会展示：
1. **ApplicationContext.refresh() 12 个阶段**的详细执行过程
2. **Bean 生命周期**的 8 个步骤（实例化 → 属性填充 → Aware → BPP → 初始化 → 销毁）
3. **循环依赖解决**过程（三级缓存的使用）
4. **AOP 代理创建**和拦截器链执行
5. **声明式事务**的开启、提交、回滚
6. **事件发布**和监听器响应
7. **BeanPostProcessor** 和 **BeanFactoryPostProcessor** 的执行时机

## 学习要点

### Spring ApplicationContext.refresh() 完整流程（12 个阶段）

1. **prepareRefresh()** - 准备刷新
   - 设置启动时间
   - 初始化属性源
   - 验证必需属性

2. **obtainFreshBeanFactory()** - 获取 BeanFactory
   - 刷新 BeanFactory
   - 加载 Bean 定义

3. **prepareBeanFactory()** - 准备 BeanFactory
   - 设置类加载器
   - 注册默认的 BeanPostProcessor
   - 注册环境 Bean

4. **postProcessBeanFactory()** - 后置处理 BeanFactory
   - 子类扩展点

5. **invokeBeanFactoryPostProcessors()** - 调用 BeanFactory 后置处理器
   - 调用 BeanDefinitionRegistryPostProcessor
   - 调用 BeanFactoryPostProcessor
   - 处理 @Configuration 类

6. **registerBeanPostProcessors()** - 注册 BeanPostProcessor
   - 注册所有 BeanPostProcessor

7. **initMessageSource()** - 初始化消息源
   - 国际化支持

8. **initApplicationEventMulticaster()** - 初始化事件多播器
   - 事件发布机制

9. **onRefresh()** - 刷新扩展点
   - 子类扩展（如 Web 容器启动）

10. **registerListeners()** - 注册监听器
    - 注册应用监听器

11. **finishBeanFactoryInitialization()** - 完成 BeanFactory 初始化
    - 冻结 Bean 定义
    - 实例化所有单例 Bean

12. **finishRefresh()** - 完成刷新
    - 发布 ContextRefreshedEvent
    - 启动生命周期处理器

### IoC 容器核心功能
1. Bean 定义注册
2. 组件扫描（@Component）
3. 依赖注入（@Autowired）
4. 单例管理
5. Bean 生命周期管理

### AOP 代理原理
1. 创建 JDK 动态代理
2. 拦截器链的构建
3. 方法调用的拦截
4. 通知的执行顺序（Before → Around(前) → 目标方法 → Around(后) → After）
5. 责任链模式的应用

### 循环依赖解决原理（三级缓存）

Spring 使用三级缓存来解决循环依赖问题：

```
ServiceA 依赖 ServiceB
ServiceB 依赖 ServiceA

解决过程：
1. 创建 ServiceA 实例，将 ServiceA 的工厂方法放入三级缓存（singletonFactories）
2. 填充 ServiceA 的属性，发现需要 ServiceB，开始创建 ServiceB
3. 创建 ServiceB 实例，将 ServiceB 的工厂方法放入三级缓存
4. 填充 ServiceB 的属性，发现需要 ServiceA
5. 从三级缓存获取 ServiceA 的工厂方法，调用工厂方法获取 ServiceA 的早期引用
6. 将 ServiceA 的早期引用放入二级缓存（earlySingletonObjects），从三级缓存移除
7. ServiceB 完成初始化，放入一级缓存（singletonObjects）
8. ServiceA 继续完成属性填充和初始化，放入一级缓存
```

**为什么需要三级缓存？**

1. **一级缓存（singletonObjects）**: 存放完全初始化的 Bean，保证单例
2. **二级缓存（earlySingletonObjects）**: 缓存早期 Bean 引用，避免重复调用工厂方法
3. **三级缓存（singletonFactories）**: 存放 Bean 工厂，支持以下功能：
   - **延迟代理对象的创建**: 只有在真正发生循环依赖时才创建代理对象
   - **保证代理对象的唯一性**: 通过工厂方法确保多次获取返回同一个代理对象
   - **支持 AOP**: 如果 Bean 需要被代理，工厂方法会返回代理对象而不是原始对象

**三级缓存的查找顺序**:
1. 先从一级缓存查找完全初始化的 Bean
2. 如果找不到且 Bean 正在创建中，从二级缓存查找早期引用
3. 如果还找不到，从三级缓存获取工厂方法，调用工厂方法获取早期引用
4. 将早期引用放入二级缓存，从三级缓存移除

### 事务管理原理
1. 通过 AOP 拦截 @Transactional 方法
2. 方法执行前开启事务
3. 方法正常执行则提交事务
4. 方法抛出异常时按回滚规则完成事务：默认 RuntimeException / Error 回滚，受检异常提交后继续抛出
5. 使用 ThreadLocal 保证事务隔离

## 技术要点

### 设计模式应用
- **工厂模式**: BeanFactory 创建和管理 Bean
- **单例模式**: 单例 Bean 的缓存管理
- **代理模式**: JDK 动态代理实现 AOP
- **责任链模式**: 拦截器链、BeanPostProcessor 链
- **观察者模式**: 事件发布与监听机制
- **模板方法模式**: ApplicationContext.refresh() 的 12 个步骤

### 核心技术
- **Java 反射**: Bean 的实例化和依赖注入
- **JDK 动态代理**: AOP 代理实现
- **注解处理**: @Component、@Autowired、@Transactional
- **ThreadLocal**: 事务上下文管理
- **泛型**: 事件监听器的类型安全

## 与真实 Spring 的对比

### 已实现的核心机制
✅ ApplicationContext.refresh() 完整流程  
✅ Bean 完整生命周期管理  
✅ 循环依赖解决（三级缓存）  
✅ BeanPostProcessor 扩展点  
✅ BeanFactoryPostProcessor 扩展点  
✅ Aware 接口回调  
✅ InitializingBean 和 DisposableBean  
✅ JDK 动态代理 AOP  
✅ CGLIB 代理支持  
✅ 方法拦截器链  
✅ 声明式事务管理  
✅ 事务传播状态模拟（7 种传播行为，不含真实保存点）
✅ 事件发布与监听  
✅ 组件扫描和自动注册  
✅ @Configuration 和 @Bean 注解  
✅ 完整的作用域支持（singleton、prototype 等）  
✅ SpEL 表达式解析  
✅ Aspect 切点表达式（execution、within、@annotation）  

### 简化说明
本项目为教学目的的简化实现，与真实 Spring 框架相比：
- CGLIB 代理使用简化实现（真实 Spring 使用 ASM 字节码生成）
- SpEL 表达式支持基础功能（真实 Spring 支持完整的表达式语法）
- 切点表达式支持常用模式（真实 Spring 支持完整的 AspectJ 语法）
- Web 作用域（request、session、application）简化为 prototype 处理

## 使用示例

### 1. Bean 作用域使用

```java
// Singleton Bean（默认）
@Component
public class SingletonService {
    // 容器中只有一个实例
}

// Prototype Bean
@Component
@Scope(BeanScope.PROTOTYPE)
public class PrototypeService {
    // 每次获取都创建新实例
}
```

### 2. Java 配置使用

```java
@Configuration
public class AppConfig {
    
    @Bean
    public DataSource dataSource() {
        return new DataSource("jdbc:mysql://localhost:3306/test");
    }
    
    @Bean(scope = BeanScope.PROTOTYPE)
    public Connection connection() {
        return new Connection();
    }
}
```

### 3. SpEL 表达式使用

```java
@Component
public class MyService {
    
    // 属性占位符
    @Value("${server.port}")
    private int port;
    
    // SpEL 表达式
    @Value("#{systemProperties['user.name']}")
    private String userName;
    
    // 运算表达式
    @Value("#{10 + 20}")
    private int result;
}
```

### 4. 切点表达式使用

```java
@Aspect
@Component
public class LoggingAspect {
    
    // 匹配所有 service 包下的方法
    @Pointcut("execution(* com.example.service.*.*(..))")
    public void serviceMethods() {}
    
    // 前置通知
    @Before("serviceMethods()")
    public void logBefore() {
        System.out.println("Method execution started");
    }
    
    // 后置通知
    @After("serviceMethods()")
    public void logAfter() {
        System.out.println("Method execution completed");
    }
    
    // 匹配带有 @Transactional 注解的方法
    @Around("@annotation(Transactional)")
    public Object logTransaction(ProceedingJoinPoint pjp) throws Throwable {
        System.out.println("Transaction started");
        Object result = pjp.proceed();
        System.out.println("Transaction completed");
        return result;
    }
}
```

### 5. 事务传播使用

```java
@Service
public class OrderService {
    
    // 默认传播行为：REQUIRED
    @Transactional
    public void createOrder() {
        // 如果当前有事务则加入，否则创建新事务
    }
    
    // 总是创建新事务
    @Transactional(propagation = "REQUIRES_NEW")
    public void saveLog() {
        // 挂起当前事务，创建新事务
        // 即使外部事务回滚，日志也会保存
    }
    
    // 嵌套事务
    @Transactional(propagation = "NESTED")
    public void saveDetail() {
        // 嵌套事务可以独立回滚
        // 但依赖外部事务的提交
    }
}
```

## 实现亮点

### 1. 事务传播机制
使用 `Stack<TransactionStatus>` 实现嵌套事务和事务挂起：
- 支持 7 种传播行为
- 正确处理事务的挂起和恢复
- 嵌套事务可以独立回滚

### 2. 切点表达式解析
使用正则表达式解析和匹配切点表达式：
- 支持 execution 表达式（方法匹配）
- 支持 within 表达式（类型匹配）
- 支持 @annotation 表达式（注解匹配）
- 支持通配符 `*` 和 `..`

### 3. SpEL 表达式解析
支持多种表达式类型：
- 字面量：字符串、数字、布尔值
- 属性占位符：`${property.name}`
- SpEL 表达式：`#{expression}`
- 属性访问：`systemProperties['key']`
- 运算符：算术、比较、逻辑运算

### 4. 作用域管理
区分不同作用域的缓存策略：
- Singleton：使用一级和二级缓存
- Prototype：每次创建新实例，不缓存
- 其他作用域：简化为 prototype 处理

### 5. 工厂方法支持
通过 @Bean 方法创建 Bean：
- 支持复杂的初始化逻辑
- 支持第三方库的 Bean 配置
- 支持方法参数依赖注入

## 测试验证

所有功能已通过测试验证：

```bash
# 编译项目
javac -d bin $(find src/main/java -name '*.java')

# 运行增强功能演示
java -cp bin com.spring.simulation.examples.context.SpringEnhancedDemo
```

**测试结果**：
- ✅ CGLIB 代理创建成功
- ✅ Singleton 作用域：同一实例
- ✅ Prototype 作用域：不同实例
- ✅ SpEL 表达式正确解析
- ✅ 切点表达式正确匹配
- ✅ 事务传播行为正确执行
- ✅ @Configuration 和 @Bean 正常工作

## 项目特点

1. **功能完整**：实现了 Spring 框架的核心功能
2. **结构清晰**：代码组织合理，易于理解
3. **注释详细**：每个关键点都有详细的注释说明
4. **设计模式**：展示了工厂、单例、代理、责任链、观察者、模板方法等模式
5. **教学友好**：适合学习 Spring 框架的底层原理

## 学习路径建议

1. **入门**：从 `com.spring.simulation.examples.context.SpringRefreshDemo` 开始，沿着容器主线理解 IoC、注入、AOP 是如何串起来的
2. **进阶**：研究 `com.spring.simulation.examples.context.SpringAdvancedDemo`，掌握生命周期、循环依赖、事务管理
4. **增强**：探索 `com.spring.simulation.examples.context.SpringEnhancedDemo`，学习作用域、SpEL、切点表达式等扩展特性

## 贡献

欢迎提交 Issue 和 Pull Request 来改进这个项目！

## 许可证

MIT License
