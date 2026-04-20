# AOP 拦截器执行顺序 - 可视化总结

## 核心问题答案

### 1. 拦截器是按照添加顺序依次执行的吗？
**✅ 是的！通过 List 实现，按添加顺序执行。**

### 2. Around 为什么会打印 2 次？
**✅ 因为 Around 在 `invocation.proceed()` 前后都有逻辑！**

---

## 可视化执行流程

运行 `AopExecutionOrderDemo.java` 的输出：

```
添加拦截器顺序：
  1. BeforeAdvice (只在 proceed 之前执行)
  2. AroundAdvice (在 proceed 前后都执行)
  3. AfterAdvice (只在 proceed 之后执行)

========== 开始调用 proxy.save("Alice") ==========

    ┌─→ [BeforeAdvice] 进入
    │   [BeforeAdvice] 执行 before 逻辑
    │   [BeforeAdvice] 调用 invocation.proceed()...
        ┌─→ [AroundAdvice] 进入
        │   [AroundAdvice] 执行 before 逻辑 ← 第 1 次打印
        │   [AroundAdvice] 调用 invocation.proceed()...
            ┌─→ [AfterAdvice] 进入
            │   [AfterAdvice] 调用 invocation.proceed()...
        │   │   │   [目标方法] 保存用户: Alice
            │   [AfterAdvice] proceed() 返回
            │   [AfterAdvice] 执行 after 逻辑（finally 块）
            └─→ [AfterAdvice] 退出
        │   [AroundAdvice] proceed() 返回
        │   [AroundAdvice] 执行 after 逻辑 ← 第 2 次打印
        └─→ [AroundAdvice] 退出
    │   [BeforeAdvice] proceed() 返回
    └─→ [BeforeAdvice] 退出（没有 after 逻辑）

========== 调用完成 ==========
```

---

## 执行顺序分析

### 时间线（从上到下）

| 步骤 | 拦截器 | 动作 | 说明 |
|-----|--------|------|------|
| 1 | BeforeAdvice | 进入 | 开始执行第 1 个拦截器 |
| 2 | BeforeAdvice | before 逻辑 | **打印 1 次** |
| 3 | BeforeAdvice | 调用 proceed() | 触发下一个拦截器 |
| 4 | AroundAdvice | 进入 | 开始执行第 2 个拦截器 |
| 5 | AroundAdvice | before 逻辑 | **第 1 次打印** |
| 6 | AroundAdvice | 调用 proceed() | 触发下一个拦截器 |
| 7 | AfterAdvice | 进入 | 开始执行第 3 个拦截器 |
| 8 | AfterAdvice | 调用 proceed() | 触发目标方法 |
| 9 | **目标方法** | **执行** | **核心业务逻辑** |
| 10 | AfterAdvice | after 逻辑 | **打印 1 次**（finally 块） |
| 11 | AfterAdvice | 退出 | 返回到 AroundAdvice |
| 12 | AroundAdvice | after 逻辑 | **第 2 次打印** |
| 13 | AroundAdvice | 退出 | 返回到 BeforeAdvice |
| 14 | BeforeAdvice | 退出 | 没有 after 逻辑，直接返回 |

---

## 关键代码对比

### BeforeAdvice（只打印 1 次）

```java
public Object invoke(..., MethodInvocation invocation) throws Throwable {
    System.out.println("before 逻辑");  // ← 唯一的打印
    return invocation.proceed();        // 直接返回，没有后续逻辑
}
```

### AroundAdvice（打印 2 次）

```java
public Object invoke(..., MethodInvocation invocation) throws Throwable {
    System.out.println("before 逻辑");  // ← 第 1 次打印
    
    Object result = invocation.proceed();  // 阻塞等待
    
    System.out.println("after 逻辑");   // ← 第 2 次打印
    return result;
}
```

### AfterAdvice（只打印 1 次）

```java
public Object invoke(..., MethodInvocation invocation) throws Throwable {
    try {
        return invocation.proceed();    // 没有 before 逻辑
    } finally {
        System.out.println("after 逻辑");  // ← 唯一的打印
    }
}
```

---

## 洋葱模型图解

```
┌─────────────────────────────────────────────────────┐
│ BeforeAdvice                                        │
│   before 逻辑 ✓                                     │
│   ┌───────────────────────────────────────────────┐ │
│   │ AroundAdvice                                  │ │
│   │   before 逻辑 ✓ (第 1 次)                     │ │
│   │   ┌─────────────────────────────────────────┐ │ │
│   │   │ AfterAdvice                             │ │ │
│   │   │   ┌───────────────────────────────────┐ │ │ │
│   │   │   │ 目标方法                          │ │ │ │
│   │   │   │   save("Alice")                   │ │ │ │
│   │   │   └───────────────────────────────────┘ │ │ │
│   │   │   after 逻辑 ✓                          │ │ │
│   │   └─────────────────────────────────────────┘ │ │
│   │   after 逻辑 ✓ (第 2 次)                     │ │
│   └───────────────────────────────────────────────┘ │
│   (没有 after 逻辑)                                 │
└─────────────────────────────────────────────────────┘

执行顺序：
  进入 → BeforeAdvice.before → AroundAdvice.before → AfterAdvice → 目标方法
  返回 ← (无)                ← AroundAdvice.after  ← AfterAdvice.after
```

---

## 为什么是这个顺序？

### 1. List 保证添加顺序

```java
private List<MethodInterceptor> interceptors = new ArrayList<>();

aopProxy.addInterceptor(new BeforeAdvice());   // interceptors[0]
aopProxy.addInterceptor(new AroundAdvice());   // interceptors[1]
aopProxy.addInterceptor(new AfterAdvice());    // interceptors[2]
```

### 2. 索引递增控制执行

```java
private int currentInterceptorIndex = -1;

public Object proceed() {
    currentInterceptorIndex++;  // -1 → 0 → 1 → 2
    MethodInterceptor interceptor = interceptors.get(currentInterceptorIndex);
    return interceptor.invoke(...);
}
```

### 3. proceed() 是阻塞调用

```java
// AroundAdvice 的执行
System.out.println("before");           // 立即执行
Object result = invocation.proceed();   // 阻塞，等待后续拦截器和目标方法执行完
System.out.println("after");            // proceed() 返回后才执行
```

---

## 调用栈分析

```
调用栈（从上到下）：

main()
  └─ proxy.save()
      └─ JdkDynamicAopProxy.invoke()
          └─ invocation.proceed()  [index: -1 → 0]
              └─ BeforeAdvice.invoke()
                  ├─ before 逻辑
                  └─ invocation.proceed()  [index: 0 → 1]
                      └─ AroundAdvice.invoke()
                          ├─ before 逻辑 (第 1 次打印)
                          └─ invocation.proceed()  [index: 1 → 2]
                              └─ AfterAdvice.invoke()
                                  └─ invocation.proceed()  [index: 2 → 3]
                                      └─ 目标方法.save()
                                      ↑ 返回
                                  └─ after 逻辑 (finally)
                              ↑ 返回
                          └─ after 逻辑 (第 2 次打印)
                      ↑ 返回
                  └─ (没有 after 逻辑)
              ↑ 返回
```

---

## 总结

### 核心原理

1. **List 保证顺序**：拦截器按添加顺序存储在 `ArrayList` 中
2. **索引递增执行**：通过 `currentInterceptorIndex++` 顺序访问拦截器
3. **proceed() 阻塞**：每次调用 `proceed()` 都会等待后续拦截器和目标方法执行完
4. **递归回溯**：目标方法返回后，递归回溯，执行每个拦截器的 after 逻辑

### 为什么 Around 打印 2 次？

| 拦截器类型 | proceed() 之前 | proceed() 之后 | 打印次数 |
|-----------|---------------|---------------|---------|
| Before | ✅ 有逻辑 | ❌ 无逻辑 | 1 次 |
| Around | ✅ 有逻辑 | ✅ 有逻辑 | **2 次** |
| After | ❌ 无逻辑 | ✅ 有逻辑 | 1 次 |

**Around 在 `proceed()` 前后都有逻辑，所以打印 2 次！**

---

## 运行演示

```bash
# 编译
javac -d bin -sourcepath src/main/java src/main/java/com/spring/simulation/AopExecutionOrderDemo.java

# 运行
java -cp bin com.spring.simulation.AopExecutionOrderDemo
```

输出会清晰地展示每个拦截器的进入、执行、退出过程，以及 Around 为什么打印 2 次。
