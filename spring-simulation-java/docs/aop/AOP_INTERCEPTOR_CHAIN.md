# AOP 拦截器链执行顺序详解

## 核心问题：拦截器是按照添加顺序依次执行的吗？

**答案：是的！通过 List 实现，按添加顺序执行。**

---

## 代码示例

```java
JdkDynamicAopProxy aopProxy = new JdkDynamicAopProxy(userRepository);
aopProxy.addInterceptor(new BeforeAdvice("LoggingAspect"));      // 第 1 个
aopProxy.addInterceptor(new AroundAdvice("PerformanceAspect"));  // 第 2 个
aopProxy.addInterceptor(new AfterAdvice("CleanupAspect"));       // 第 3 个
```

---

## 实现原理：List + 责任链模式

### 1. 拦截器存储：使用 List

```java
public class JdkDynamicAopProxy implements InvocationHandler {
    // 拦截器链：按添加顺序存储
    private List<MethodInterceptor> interceptors = new ArrayList<>();
    
    public void addInterceptor(MethodInterceptor interceptor) {
        interceptors.add(interceptor);  // 按顺序添加到 List 末尾
    }
}
```

**关键点：**
- 使用 `ArrayList` 保证插入顺序
- `add()` 方法将拦截器追加到列表末尾
- 执行时按索引 0, 1, 2... 顺序访问

---

### 2. 拦截器执行：责任链模式

```java
private static class ReflectiveMethodInvocation implements MethodInvocation {
    private List<MethodInterceptor> interceptors;
    private int currentInterceptorIndex = -1;  // 当前执行到第几个拦截器
    
    @Override
    public Object proceed() throws Throwable {
        // 所有拦截器都执行完了，调用目标方法
        if (currentInterceptorIndex == interceptors.size() - 1) {
            return method.invoke(target, arguments);
        }
        
        // 执行下一个拦截器（索引递增）
        currentInterceptorIndex++;
        MethodInterceptor interceptor = interceptors.get(currentInterceptorIndex);
        return interceptor.invoke(null, method, arguments, this);
    }
}
```

**执行流程：**
1. `currentInterceptorIndex` 从 -1 开始
2. 每次调用 `proceed()` 时，索引 +1
3. 按索引顺序从 List 中取出拦截器：`interceptors.get(0)`, `interceptors.get(1)`, `interceptors.get(2)`...
4. 最后一个拦截器执行完后，调用目标方法

---

## 执行顺序详解

### 添加顺序
```java
aopProxy.addInterceptor(new BeforeAdvice("LoggingAspect"));      // interceptors[0]
aopProxy.addInterceptor(new AroundAdvice("PerformanceAspect"));  // interceptors[1]
aopProxy.addInterceptor(new AfterAdvice("CleanupAspect"));       // interceptors[2]
```

### 执行流程（调用 save() 方法）

```
1. 代理对象.save() 被调用
   ↓
2. JdkDynamicAopProxy.invoke() 拦截
   ↓
3. 创建 ReflectiveMethodInvocation，开始执行拦截器链
   ↓
4. proceed() 第 1 次调用 (index: -1 → 0)
   → 执行 interceptors[0]: BeforeAdvice
   → 输出: [AOP-Before] LoggingAspect - Before method: save
   → 调用 invocation.proceed() 继续
   ↓
5. proceed() 第 2 次调用 (index: 0 → 1)
   → 执行 interceptors[1]: AroundAdvice
   → 输出: [AOP-Around] PerformanceAspect - Around before: save
   → 调用 invocation.proceed() 继续
   ↓
6. proceed() 第 3 次调用 (index: 1 → 2)
   → 执行 interceptors[2]: AfterAdvice
   → 调用 invocation.proceed() 继续
   ↓
7. proceed() 第 4 次调用 (index: 2 → 3)
   → index == interceptors.size() - 1，执行目标方法
   → 输出: [UserRepository] Saved to database: Alice
   ↓
8. 目标方法返回，开始回溯
   ↓
9. AfterAdvice 的 finally 块执行
   → 输出: [AOP-After] CleanupAspect - After method: save
   ↓
10. AroundAdvice 的 after 逻辑执行
    → 输出: [AOP-Around] PerformanceAspect - Around after: save (took 0ms)
    ↓
11. BeforeAdvice 返回（没有 after 逻辑）
    ↓
12. 方法调用完成
```

---

## 实际输出验证

```
--- Call 1: save() ---
[AOP-Before] LoggingAspect - Before method: save           ← 第 1 个拦截器 before
[AOP-Around] PerformanceAspect - Around before: save       ← 第 2 个拦截器 before
        [UserRepository] Saved to database: Alice          ← 目标方法执行
[AOP-After] CleanupAspect - After method: save             ← 第 3 个拦截器 after
[AOP-Around] PerformanceAspect - Around after: save        ← 第 2 个拦截器 after
                                                           ← 第 1 个拦截器没有 after
```

---

## 执行顺序图解

```
添加顺序：BeforeAdvice → AroundAdvice → AfterAdvice

执行顺序（洋葱模型）：

    BeforeAdvice.invoke() {
        before 逻辑                          ← 1. 最外层 before
        ↓
        AroundAdvice.invoke() {
            before 逻辑                      ← 2. 中间层 before
            ↓
            AfterAdvice.invoke() {
                try {
                    ↓
                    目标方法.save()          ← 3. 核心方法
                    ↑
                } finally {
                    after 逻辑               ← 4. 最内层 after
                }
            }
            ↑
            after 逻辑                       ← 5. 中间层 after
        }
        ↑
        (没有 after 逻辑)                    ← 6. 最外层返回
    }
```

---

## 为什么使用 List？

### 1. 保证顺序
```java
List<MethodInterceptor> interceptors = new ArrayList<>();
// ArrayList 保证元素的插入顺序
// 遍历时按索引 0, 1, 2... 顺序访问
```

### 2. 支持动态添加
```java
aopProxy.addInterceptor(interceptor1);
aopProxy.addInterceptor(interceptor2);
aopProxy.addInterceptor(interceptor3);
// 可以动态添加任意数量的拦截器
```

### 3. 索引控制执行
```java
private int currentInterceptorIndex = -1;

public Object proceed() {
    currentInterceptorIndex++;  // 索引递增
    MethodInterceptor interceptor = interceptors.get(currentInterceptorIndex);
    return interceptor.invoke(...);
}
```

---

## 责任链模式的关键

### 1. 每个拦截器都持有 MethodInvocation 引用
```java
public Object invoke(Object proxy, Method method, Object[] args, 
                     MethodInvocation invocation) throws Throwable {
    // before 逻辑
    Object result = invocation.proceed();  // 触发下一个拦截器
    // after 逻辑
    return result;
}
```

### 2. 拦截器可以决定是否继续执行
```java
// 正常情况：继续执行
return invocation.proceed();

// 短路：不继续执行（如权限校验失败）
if (!hasPermission()) {
    throw new SecurityException("No permission");
}
return invocation.proceed();
```

### 3. 拦截器可以修改返回值
```java
Object result = invocation.proceed();
// 修改返回值
return modifyResult(result);
```

---

## 与 Spring AOP 的对比

### Spring AOP 的拦截器顺序

```java
@Aspect
@Order(1)  // 优先级：数字越小越先执行
public class LoggingAspect {
    @Before("execution(* com.example..*(..))")
    public void before() { }
}

@Aspect
@Order(2)
public class PerformanceAspect {
    @Around("execution(* com.example..*(..))")
    public Object around(ProceedingJoinPoint pjp) { }
}
```

**Spring 的实现：**
- 使用 `@Order` 或 `Ordered` 接口控制顺序
- 内部也是通过 List 存储拦截器
- 按 Order 值排序后，执行逻辑与本项目相同

---

## 总结

| 特性 | 实现方式 |
|------|---------|
| **存储结构** | `List<MethodInterceptor>` |
| **添加方式** | `interceptors.add(interceptor)` 按顺序追加 |
| **执行方式** | 通过索引递增 `interceptors.get(index++)` 顺序访问 |
| **设计模式** | 责任链模式（Chain of Responsibility） |
| **执行顺序** | 按添加顺序：before 正序，after 逆序（洋葱模型） |

**关键点：**
1. ✅ 拦截器按添加顺序执行
2. ✅ 通过 List 实现顺序存储
3. ✅ 通过索引递增实现顺序访问
4. ✅ 形成洋葱模型：before 正序，after 逆序
