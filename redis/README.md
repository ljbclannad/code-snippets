# Redis 实现集合

## 项目简介

本项目包含多个 Redis 相关的 Java 实现，涵盖了缓存、数据结构、分布式锁等核心场景。通过从零实现 Redis 的部分功能，深入理解 Redis 的工作原理和应用模式。

## 技术栈

- **语言**: Java
- **依赖**: 仅使用 JDK 标准库
- **涉及技术**: 
  - 网络编程（Socket）
  - 数据结构（跳表、哈希表）
  - 并发控制（锁、CAS）
  - 缓存策略（LRU、逻辑过期）

## 项目结构

```
redis/
├── MiniRedisServer.java        # 迷你 Redis 服务器实现
├── RedisZSetSkipList.java      # 有序集合（跳表实现）
├── LogicalExpireCache.java     # 逻辑过期缓存
└── PayCallbackDemo.java        # 支付回调幂等性实现
```

## 核心模块

### 1. MiniRedisServer - 迷你 Redis 服务器

**文件**: `MiniRedisServer.java`

**功能**: 从零实现一个简化版的 Redis 服务器，支持基本的 Redis 命令

**支持的命令**:
- `SET key value`: 设置键值对
- `GET key`: 获取值
- `DEL key`: 删除键
- `EXISTS key`: 检查键是否存在
- `EXPIRE key seconds`: 设置过期时间
- `TTL key`: 查看剩余过期时间
- `INCR key`: 自增
- `DECR key`: 自减
- `LPUSH key value`: 左侧插入列表
- `RPUSH key value`: 右侧插入列表
- `LPOP key`: 左侧弹出
- `RPOP key`: 右侧弹出
- `LRANGE key start stop`: 获取列表范围

**核心实现**:

```java
public class MiniRedisServer {
    private final Map<String, Object> dataStore = new ConcurrentHashMap<>();
    private final Map<String, Long> expireMap = new ConcurrentHashMap<>();
    private final int port;
    
    public MiniRedisServer(int port) {
        this.port = port;
    }
    
    public void start() throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Mini Redis Server started on port " + port);
        
        while (true) {
            Socket clientSocket = serverSocket.accept();
            new Thread(() -> handleClient(clientSocket)).start();
        }
    }
    
    private void handleClient(Socket socket) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            String command;
            while ((command = in.readLine()) != null) {
                String response = executeCommand(command);
                out.println(response);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private String executeCommand(String command) {
        String[] parts = command.split(" ", 3);
        String cmd = parts[0].toUpperCase();
        
        switch (cmd) {
            case "SET":
                return handleSet(parts[1], parts[2]);
            case "GET":
                return handleGet(parts[1]);
            case "DEL":
                return handleDel(parts[1]);
            case "EXPIRE":
                return handleExpire(parts[1], Long.parseLong(parts[2]));
            case "INCR":
                return handleIncr(parts[1]);
            // ... 其他命令
            default:
                return "ERROR: Unknown command";
        }
    }
    
    private String handleSet(String key, String value) {
        dataStore.put(key, value);
        return "OK";
    }
    
    private String handleGet(String key) {
        if (isExpired(key)) {
            dataStore.remove(key);
            expireMap.remove(key);
            return "(nil)";
        }
        Object value = dataStore.get(key);
        return value != null ? value.toString() : "(nil)";
    }
    
    private boolean isExpired(String key) {
        Long expireTime = expireMap.get(key);
        if (expireTime == null) return false;
        return System.currentTimeMillis() > expireTime;
    }
}
```

**运行示例**:

```bash
# 启动服务器
javac MiniRedisServer.java
java MiniRedisServer

# 使用 telnet 连接
telnet localhost 6379

# 执行命令
SET name "Redis"
GET name
EXPIRE name 10
TTL name
```

**特点**:
- ✅ 多线程处理客户端连接
- ✅ 支持过期时间
- ✅ 支持多种数据类型（String, List）
- ✅ 简单的 RESP 协议实现

---

### 2. RedisZSetSkipList - 有序集合（跳表）

**文件**: `RedisZSetSkipList.java`

**功能**: 实现 Redis 有序集合（ZSet）的底层数据结构 - 跳表（Skip List）

**跳表原理**:
- 多层链表结构
- 每层都是有序链表
- 上层是下层的"快速通道"
- 查找、插入、删除时间复杂度：O(log n)

**核心实现**:

```java
public class RedisZSetSkipList {
    private static final int MAX_LEVEL = 32;
    private static final double P = 0.25;
    
    private class Node {
        String member;
        double score;
        Node[] forward;
        
        Node(String member, double score, int level) {
            this.member = member;
            this.score = score;
            this.forward = new Node[level];
        }
    }
    
    private Node head;
    private int level;
    private int size;
    
    public RedisZSetSkipList() {
        this.head = new Node(null, 0, MAX_LEVEL);
        this.level = 1;
        this.size = 0;
    }
    
    // 插入元素
    public void add(String member, double score) {
        Node[] update = new Node[MAX_LEVEL];
        Node current = head;
        
        // 从最高层开始查找插入位置
        for (int i = level - 1; i >= 0; i--) {
            while (current.forward[i] != null && 
                   current.forward[i].score < score) {
                current = current.forward[i];
            }
            update[i] = current;
        }
        
        // 随机生成层数
        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; i++) {
                update[i] = head;
            }
            level = newLevel;
        }
        
        // 创建新节点并插入
        Node newNode = new Node(member, score, newLevel);
        for (int i = 0; i < newLevel; i++) {
            newNode.forward[i] = update[i].forward[i];
            update[i].forward[i] = newNode;
        }
        
        size++;
    }
    
    // 查找元素
    public Double getScore(String member) {
        Node current = head;
        for (int i = level - 1; i >= 0; i--) {
            while (current.forward[i] != null) {
                if (current.forward[i].member.equals(member)) {
                    return current.forward[i].score;
                }
                if (current.forward[i].score > current.score) {
                    break;
                }
                current = current.forward[i];
            }
        }
        return null;
    }
    
    // 范围查询
    public List<String> rangeByScore(double min, double max) {
        List<String> result = new ArrayList<>();
        Node current = head.forward[0];
        
        while (current != null && current.score <= max) {
            if (current.score >= min) {
                result.add(current.member);
            }
            current = current.forward[0];
        }
        
        return result;
    }
    
    // 随机生成层数
    private int randomLevel() {
        int level = 1;
        while (Math.random() < P && level < MAX_LEVEL) {
            level++;
        }
        return level;
    }
}
```

**使用示例**:

```java
RedisZSetSkipList zset = new RedisZSetSkipList();

// 添加元素
zset.add("Alice", 95.5);
zset.add("Bob", 87.0);
zset.add("Charlie", 92.3);

// 查询分数
Double score = zset.getScore("Alice");  // 95.5

// 范围查询
List<String> top = zset.rangeByScore(90.0, 100.0);
// ["Charlie", "Alice"]
```

**运行示例**:
```bash
javac RedisZSetSkipList.java
java RedisZSetSkipList
```

**特点**:
- ✅ O(log n) 的查找、插入、删除
- ✅ 支持范围查询
- ✅ 空间换时间的经典案例
- ✅ Redis ZSet 的底层实现之一

---

### 3. LogicalExpireCache - 逻辑过期缓存

**文件**: `LogicalExpireCache.java`

**功能**: 实现逻辑过期的缓存策略，解决缓存击穿问题

**逻辑过期 vs 物理过期**:

| 特性 | 物理过期 | 逻辑过期 |
|------|---------|---------|
| 过期处理 | 自动删除 | 标记过期但不删除 |
| 缓存击穿 | 可能发生 | 不会发生 |
| 数据一致性 | 强一致 | 最终一致 |
| 适用场景 | 一般缓存 | 高并发热点数据 |

**核心实现**:

```java
public class LogicalExpireCache<K, V> {
    
    private static class CacheData<V> {
        V data;
        long expireTime;
        
        CacheData(V data, long expireTime) {
            this.data = data;
            this.expireTime = expireTime;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }
    }
    
    private final Map<K, CacheData<V>> cache = new ConcurrentHashMap<>();
    private final Map<K, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    
    // 获取数据
    public V get(K key, Function<K, V> dbLoader, long ttlSeconds) {
        CacheData<V> cacheData = cache.get(key);
        
        // 缓存未命中
        if (cacheData == null) {
            return loadAndCache(key, dbLoader, ttlSeconds);
        }
        
        // 缓存命中但未过期
        if (!cacheData.isExpired()) {
            return cacheData.data;
        }
        
        // 缓存命中但已过期（逻辑过期）
        // 先返回旧数据，异步更新
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        
        if (lock.tryLock()) {
            try {
                // 双重检查
                cacheData = cache.get(key);
                if (cacheData != null && !cacheData.isExpired()) {
                    return cacheData.data;
                }
                
                // 异步更新缓存
                executor.submit(() -> {
                    V newData = dbLoader.apply(key);
                    cache.put(key, new CacheData<>(
                        newData,
                        System.currentTimeMillis() + ttlSeconds * 1000
                    ));
                });
            } finally {
                lock.unlock();
            }
        }
        
        // 返回旧数据（即使已过期）
        return cacheData.data;
    }
    
    private V loadAndCache(K key, Function<K, V> dbLoader, long ttlSeconds) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            // 双重检查
            CacheData<V> cacheData = cache.get(key);
            if (cacheData != null) {
                return cacheData.data;
            }
            
            // 从数据库加载
            V data = dbLoader.apply(key);
            if (data != null) {
                cache.put(key, new CacheData<>(
                    data,
                    System.currentTimeMillis() + ttlSeconds * 1000
                ));
            }
            return data;
        } finally {
            lock.unlock();
        }
    }
}
```

**使用示例**:

```java
LogicalExpireCache<String, User> userCache = new LogicalExpireCache<>();

// 获取用户（自动处理缓存）
User user = userCache.get(
    "user:1001",
    userId -> userService.getUserById(userId),  // 数据库加载函数
    300  // TTL 300 秒
);
```

**运行示例**:
```bash
javac LogicalExpireCache.java
java LogicalExpireCache
```

**优势**:
- ✅ 避免缓存击穿（永远返回数据）
- ✅ 异步更新，不阻塞请求
- ✅ 适合高并发场景
- ⚠️ 可能返回过期数据（最终一致性）

---

### 4. PayCallbackDemo - 支付回调幂等性

**文件**: `PayCallbackDemo.java`

**功能**: 实现支付回调的幂等性处理，防止重复处理

**幂等性问题**:
- 网络重试导致重复回调
- 第三方支付平台多次推送
- 需要保证订单状态只更新一次

**核心实现**:

```java
public class PayCallbackDemo {
    
    private final Map<String, String> orderStatus = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> orderLocks = new ConcurrentHashMap<>();
    
    // 处理支付回调
    public boolean handlePayCallback(String orderId, String payStatus) {
        // 获取订单锁
        ReentrantLock lock = orderLocks.computeIfAbsent(
            orderId, 
            k -> new ReentrantLock()
        );
        
        lock.lock();
        try {
            // 检查订单状态
            String currentStatus = orderStatus.get(orderId);
            
            // 已经处理过
            if ("PAID".equals(currentStatus)) {
                System.out.println("订单已支付，忽略重复回调: " + orderId);
                return false;
            }
            
            // 处理支付
            if ("SUCCESS".equals(payStatus)) {
                // 更新订单状态
                orderStatus.put(orderId, "PAID");
                
                // 执行业务逻辑
                processOrder(orderId);
                
                System.out.println("订单支付成功: " + orderId);
                return true;
            }
            
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    // 使用 Redis 实现分布式幂等
    public boolean handlePayCallbackWithRedis(
        String orderId, 
        String payStatus,
        RedisTemplate<String, String> redis
    ) {
        String lockKey = "pay:lock:" + orderId;
        String statusKey = "pay:status:" + orderId;
        
        // 尝试获取分布式锁
        Boolean locked = redis.opsForValue().setIfAbsent(
            lockKey, 
            "1", 
            10, 
            TimeUnit.SECONDS
        );
        
        if (!Boolean.TRUE.equals(locked)) {
            return false;  // 获取锁失败
        }
        
        try {
            // 检查是否已处理
            String status = redis.opsForValue().get(statusKey);
            if ("PAID".equals(status)) {
                return false;  // 已处理
            }
            
            // 处理支付
            if ("SUCCESS".equals(payStatus)) {
                redis.opsForValue().set(statusKey, "PAID", 24, TimeUnit.HOURS);
                processOrder(orderId);
                return true;
            }
            
            return false;
        } finally {
            // 释放锁
            redis.delete(lockKey);
        }
    }
    
    private void processOrder(String orderId) {
        // 发货、通知用户等业务逻辑
        System.out.println("处理订单: " + orderId);
    }
}
```

**使用示例**:

```java
PayCallbackDemo demo = new PayCallbackDemo();

// 第一次回调
demo.handlePayCallback("ORDER-001", "SUCCESS");  // true，处理成功

// 重复回调
demo.handlePayCallback("ORDER-001", "SUCCESS");  // false，忽略重复
```

**运行示例**:
```bash
javac PayCallbackDemo.java
java PayCallbackDemo
```

**幂等性保证方案**:

1. **数据库唯一索引**
```sql
CREATE UNIQUE INDEX uk_order_id ON t_payment(order_id);
```

2. **Redis SETNX**
```java
Boolean success = redis.opsForValue().setIfAbsent(key, value);
```

3. **状态机**
```java
// 只允许 UNPAID -> PAID 的状态转换
UPDATE t_order SET status = 'PAID' WHERE order_id = ? AND status = 'UNPAID'
```

4. **Token 机制**
```java
// 生成唯一 token
String token = UUID.randomUUID().toString();
redis.set("token:" + orderId, token, 300);

// 验证并删除 token（原子操作）
String script = "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) else return 0 end";
```

---

## 快速开始

### 编译所有文件

```bash
cd redis
javac *.java
```

### 运行示例

```bash
# Mini Redis Server
java MiniRedisServer

# 跳表测试
java RedisZSetSkipList

# 逻辑过期缓存
java LogicalExpireCache

# 支付回调幂等性
java PayCallbackDemo
```

## 核心概念

### 1. 跳表（Skip List）

**为什么使用跳表？**
- 有序集合需要快速查找和范围查询
- 平衡树实现复杂
- 跳表实现简单，性能接近平衡树

**跳表 vs 红黑树**:
| 特性 | 跳表 | 红黑树 |
|------|------|--------|
| 实现复杂度 | 简单 | 复杂 |
| 查找时间 | O(log n) | O(log n) |
| 插入时间 | O(log n) | O(log n) |
| 范围查询 | 简单 | 复杂 |
| 并发友好 | 是 | 否 |

### 2. 缓存策略

**缓存穿透**: 查询不存在的数据
- 解决方案：布隆过滤器、缓存空值

**缓存击穿**: 热点数据过期
- 解决方案：逻辑过期、互斥锁

**缓存雪崩**: 大量缓存同时过期
- 解决方案：随机过期时间、多级缓存

### 3. 分布式锁

**实现方式**:
```java
// SETNX + EXPIRE
SET lock:key value NX EX 10

// Lua 脚本保证原子性
String script = 
    "if redis.call('get', KEYS[1]) == ARGV[1] then " +
    "return redis.call('del', KEYS[1]) else return 0 end";
```

**注意事项**:
- 设置过期时间防止死锁
- 使用唯一标识防止误删
- 考虑锁续期（Redisson）

## 扩展实验

### 实验 1: 实现 LRU 缓存

```java
public class LRUCache<K, V> extends LinkedHashMap<K, V> {
    private final int capacity;
    
    public LRUCache(int capacity) {
        super(capacity, 0.75f, true);
        this.capacity = capacity;
    }
    
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > capacity;
    }
}
```

### 实验 2: 实现布隆过滤器

```java
public class BloomFilter {
    private BitSet bitSet;
    private int size;
    private int hashFunctions;
    
    public void add(String key) {
        for (int i = 0; i < hashFunctions; i++) {
            int hash = hash(key, i);
            bitSet.set(hash % size);
        }
    }
    
    public boolean mightContain(String key) {
        for (int i = 0; i < hashFunctions; i++) {
            int hash = hash(key, i);
            if (!bitSet.get(hash % size)) {
                return false;
            }
        }
        return true;
    }
}
```

### 实验 3: 实现分布式限流

```java
// 滑动窗口限流
public boolean isAllowed(String key, int limit, int window) {
    long now = System.currentTimeMillis();
    String redisKey = "rate_limit:" + key;
    
    // 移除过期记录
    redis.zremrangeByScore(redisKey, 0, now - window * 1000);
    
    // 统计当前窗口请求数
    Long count = redis.zcard(redisKey);
    
    if (count < limit) {
        redis.zadd(redisKey, now, UUID.randomUUID().toString());
        redis.expire(redisKey, window);
        return true;
    }
    
    return false;
}
```

## 相关资源

- [Redis 官方文档](https://redis.io/documentation)
- [Redis 设计与实现](http://redisbook.com/)
- [跳表论文](https://15721.courses.cs.cmu.edu/spring2018/papers/08-oltpindexes1/pugh-skiplists-cacm1990.pdf)

## 许可证

本项目仅供学习和演示使用。
