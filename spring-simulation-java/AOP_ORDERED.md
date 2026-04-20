# AOP 中 Ordered 的作用和生效时机

## 核心问题：AOP 的 Ordered 在哪里生效？

**答案：在创建代理对象之前，通过 `sortInterceptors()` 方法对拦截器进行排序。**

---

## Ordered 的作用

### 1. 控制拦截器的执行顺序

在 Spring AOP 中，多个切面可能作用于同一个方法。Ordered 接口用于控制这些切面的执行顺序。

```java
@Aspect
@Order(10)  // 数字越小，优先级越高
public class SecurityAspect {
    @Before("execution(* com.example..*(..))")
    public void checkSecurity() {
        // 安全检查
    }
}

@Aspect
@Order(20)
public class TransactionAspect {
    @Around("execution(* com.example..*(..))")
    public Object manageTransaction(ProceedingJoinPoint pjp) {
        // 事务管理
    }
}
```

**执行顺序：** SecurityAspect (order=10) → TransactionAspect (order=20)

---

## Ordered 的生效时机

### 在 Spring 中的生效流程

```
1. Spring 容器启动
   ↓
2. 扫描所有 @Aspect 切面
   ↓
3. 为每个切面创建 Advisor（包含 Advice 和 Pointcut）
   ↓
4. 收集所有匹配的 Advisor
   ↓
5. ★ 按 Ordered 排序 Advisor ★  ← Ordered 在这里生效
   ↓
6. 创建代理对象（JDK 动态代理或 CGLIB）
   ↓
7. 将排序后的拦截器链注入代理对象
   ↓
8. 方法调用时，按排序后的顺序执行拦截器
```

---

## 本项目中的实现

### 1. Ordered 接口定义

```java
public interface Ordered {
    int HIGHEST_PRECEDENCE = Integer.MIN_VALUE;  // 最高优先级
    int LOWEST_PRECEDENCE = Integer.MAX_VALUE;   // 最低优先级
    
    int getOrder();  // 数字越小，优先级越高
}
```

### 2. PriorityOrdered 接口

```java
public interface PriorityOrdered extends Ordered {
    // 继承自 Ordered，表示更高优先级
}
```

**优先级顺序：** PriorityOrdered > Ordered > 普通（没有实现接口）

---

## sortInterceptors() 方法详解

### 生效时机

```java
JdkDynamicAopProxy aopProxy = new JdkDynamicAopProxy(target);

// 添加拦截器（可以任意顺序添加）
aopProxy.addInterceptor(new TransactionAdvice());  // order=20
aopProxy.addInterceptor(new LoggingAdvice());      // order=30
aopProxy.addInterceptor(new SecurityAdvice());     // order=10, PriorityOrdered

// ★ 在创建代理对象之前调用 sortInterceptors() ★
aopProxy.sortInterceptors();  // ← Ordered 在这里生效

// 创建代理对象
UserService proxy = (UserService) aopProxy.getProxy();
```

### 排序规则

```java
public void sortInterceptors() {
    interceptors.sort(new Comparator<MethodInterceptor>() {
        @Override
        public int compare(MethodInterceptor o1, MethodInterceptor o2) {
            // 规则 1: 按优先级类别排序
            // PriorityOrdered (1) > Ordered (2) > 普通 (3)
            int priority1 = getPriorityLevel(o1);
            int priority2 = getPriorityLevel(o2);
            
            if (priority1 != priority2) {
                return Integer.compare(priority1, priority2);
            }
            
            // 规则 2: 同一类别内，按 Order 值排序
            // 数字越小越先执行
            int order1 = getOrder(o1);
            int order2 = getOrder(o2);
            return Integer.compare(order1, order2);
        }
    });
}
```

---

## 演示对比

### 场景 1: 不使用 Ordered（按添加顺序）

```java
// 添加顺序
aopProxy.addInterceptor(new SecurityAdvice());      // 1
aopProxy.addInterceptor(new TransactionAdvice());   // 2
aopProxy.addInterceptor(new LoggingAdvice());       // 3

// 不调用 sortInterceptors()

// 执行顺序（按添加顺序）
[SecurityAdvice] 安全检查
  [TransactionAdvice] 开启事务
    [LoggingAdvice] 记录日志
      [目标方法] 保存用户
    [TransactionAdvice] 提交事务
```

### 场景 2: 使用 Ordered（按 Order 值排序）

```java
// 添加顺序（故意打乱）
aopProxy.addInterceptor(new OrderedTransactionAdvice());  // order=20
aopProxy.addInterceptor(new OrderedLoggingAdvice());      // order=30
aopProxy.addInterceptor(new OrderedSecurityAdvice());     // order=10, PriorityOrdered

// 调用 sortInterceptors() 排序
aopProxy.sortInterceptors();

// 排序后的顺序
1. OrderedSecurityAdvice [PriorityOrdered, order=10]
2. OrderedTransactionAdvice [Ordered, order=20]
3. OrderedLoggingAdvice [Ordered, order=30]

// 执行顺序（按 Order 值）
[SecurityAdvice] 安全检查 (PriorityOrdered, order=10)
  [TransactionAdvice] 开启事务 (Ordered, order=20)
    [LoggingAdvice] 记录日志 (Ordered, order=30)
      [目标方法] 保存用户
    [TransactionAdvice] 提交事务
```

---

## 为什么需要 Ordered？

### 1. 控制切面的执行顺序

某些切面必须在其他切面之前执行：

```
安全检查 (order=10)
  ↓
事务管理 (order=20)
  ↓
日志记录 (order=30)
  ↓
目标方法
```

**原因：**
- 安全检查必须最先执行，如果没有权限，后续逻辑都不应该执行
- 事务管理应该在业务逻辑之前开启，在业务逻辑之后提交
- 日志记录可以放在最后，记录完整的执行过程

### 2. 解决切面冲突

如果两个切面都修改方法参数或返回值，执行顺序会影响最终结果。

```java
@Aspect
@Order(1)
public class ValidationAspect {
    @Before("execution(* save(..))")
    public void validate(JoinPoint jp) {
        // 参数校验
    }
}

@Aspect
@Order(2)
public class TransformAspect {
    @Before("execution(* save(..))")
    public void transform(JoinPoint jp) {
        // 参数转换
    }
}
```

**执行顺序：** 先校验，再转换（如果顺序反了，可能校验失败）

---

## Spring AOP 中 Ordered 的实际应用

### 1. 在 BeanPostProcessor 中排序

```java
// ApplicationContext.registerBeanPostProcessors()
protected void registerBeanPostProcessors() {
    // 步骤 1: 收集所有 BeanPostProcessor
    List<BeanPostProcessor> postProcessors = findBeanPostProcessors();
    
    // 步骤 2: 按 Ordered 排序
    // PriorityOrdered > Ordered > 普通
    postProcessors.sort(...);
    
    // 步骤 3: 按顺序注册
    for (BeanPostProcessor pp : postProcessors) {
        beanFactory.addBeanPostProcessor(pp);
    }
}
```

### 2. 在 AOP 代理创建时排序

```java
// AbstractAutoProxyCreator.createProxy()
protected Object createProxy(...) {
    // 步骤 1: 收集所有匹配的 Advisor
    List<Advisor> advisors = findEligibleAdvisors(beanClass);
    
    // 步骤 2: 按 Ordered 排序 Advisor
    advisors = sortAdvisors(advisors);  // ← Ordered 在这里生效
    
    // 步骤 3: 创建代理对象
    return proxyFactory.getProxy(advisors);
}
```

---

## 排序算法详解

### 优先级规则

```
优先级类别（从高到低）：
1. PriorityOrdered（最高优先级）
2. Ordered（中等优先级）
3. 普通（没有实现接口，最低优先级）

同一类别内，按 getOrder() 值排序：
- 数字越小，优先级越高
- Integer.MIN_VALUE 是最高优先级
- Integer.MAX_VALUE 是最低优先级
```

### 排序示例

```java
// 添加顺序（故意打乱）
Interceptor A: Ordered, order=30
Interceptor B: PriorityOrdered, order=20
Interceptor C: 普通（没有实现接口）
Interceptor D: Ordered, order=10
Interceptor E: PriorityOrdered, order=5

// 排序后的顺序
1. E: PriorityOrdered, order=5   ← PriorityOrdered 优先，且 5 < 20
2. B: PriorityOrdered, order=20
3. D: Ordered, order=10          ← Ordered 次之，且 10 < 30
4. A: Ordered, order=30
5. C: 普通                       ← 普通最后
```

---

## 代码实现对比

### Spring 源码中的实现

```java
// org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator
protected List<Advisor> sortAdvisors(List<Advisor> advisors) {
    AnnotationAwareOrderComparator.sort(advisors);
    return advisors;
}

// org.springframework.core.annotation.AnnotationAwareOrderComparator
public int compare(Object o1, Object o2) {
    // 1. 检查 PriorityOrdered
    boolean p1 = (o1 instanceof PriorityOrdered);
    boolean p2 = (o2 instanceof PriorityOrdered);
    if (p1 && !p2) return -1;
    if (p2 && !p1) return 1;
    
    // 2. 获取 Order 值
    int i1 = getOrder(o1);
    int i2 = getOrder(o2);
    return Integer.compare(i1, i2);
}
```

### 本项目中的实现

```java
// JdkDynamicAopProxy.sortInterceptors()
public void sortInterceptors() {
    interceptors.sort(new Comparator<MethodInterceptor>() {
        @Override
        public int compare(MethodInterceptor o1, MethodInterceptor o2) {
            // 1. 按优先级类别排序
            int priority1 = getPriorityLevel(o1);
            int priority2 = getPriorityLevel(o2);
            if (priority1 != priority2) {
                return Integer.compare(priority1, priority2);
            }
            
            // 2. 按 Order 值排序
            int order1 = getOrder(o1);
            int order2 = getOrder(o2);
            return Integer.compare(order1, order2);
        }
    });
}
```

---

## 总结

### Ordered 在 AOP 中的生效时机

| 阶段 | 操作 | Ordered 是否生效 |
|------|------|-----------------|
| 1. 添加拦截器 | `addInterceptor()` | ❌ 不生效 |
| 2. 排序拦截器 | `sortInterceptors()` | ✅ **在这里生效** |
| 3. 创建代理对象 | `getProxy()` | ❌ 已经排序完成 |
| 4. 方法调用 | `invoke()` | ❌ 按排序后的顺序执行 |

### 关键点

1. **Ordered 在创建代理对象之前生效**
2. **通过 `sortInterceptors()` 方法对拦截器进行排序**
3. **排序规则：PriorityOrdered > Ordered > 普通**
4. **同一类别内，按 `getOrder()` 值排序（数字越小越先执行）**
5. **排序后的顺序决定了拦截器的执行顺序**

### 实际应用

- 安全检查应该使用 `PriorityOrdered` 和较小的 order 值（如 10）
- 事务管理应该使用 `Ordered` 和中等的 order 值（如 20）
- 日志记录可以使用较大的 order 值（如 30）

---

## 运行演示

```bash
# 编译
javac -d bin -sourcepath src/main/java src/main/java/com/spring/simulation/AopOrderedDemo.java

# 运行
java -cp bin com.spring.simulation.AopOrderedDemo
```

输出会清晰地展示：
1. 不使用 Ordered 时，按添加顺序执行
2. 使用 Ordered 时，按 Order 值排序后执行
