# 向量记忆系统端到端集成测试

## 概述

`VectorMemoryEndToEndTest` 是向量记忆系统的端到端集成测试，验证整个系统的完整流程，包括：

1. **记忆提取流程**：从对话中提取记忆，保存到 MySQL + Milvus + Redis
2. **记忆检索流程**：Redis 缓存优先 + Milvus 相似度搜索
3. **迁移脚本验证**：历史数据迁移到新向量系统

## 测试覆盖范围

### 测试1：记忆提取流程
- ✅ 记忆成功保存到 MySQL
- ✅ embedding_id 已更新（Milvus 写入成功）
- ✅ Redis 去重索引已更新
- ✅ Redis 最近记忆缓存已更新

### 测试2：记忆检索流程
- ✅ Redis 缓存命中（最近记忆）
- ✅ Milvus 查询（相似度搜索）
- ✅ 结果合并和去重

### 测试3：Redis 去重功能
- ✅ 重复内容被 Redis 快速去重
- ✅ 不会重复写入 MySQL 和 Milvus

### 测试4：迁移脚本（Dry Run 模式）
- ✅ 统计待迁移记忆数量
- ✅ Dry Run 模式不实际写入
- ✅ 迁移统计信息正确

### 测试5：迁移脚本（实际迁移）
- ✅ 记忆成功迁移到 Milvus
- ✅ embedding_id 已更新
- ✅ Redis 索引已更新
- ✅ 迁移后可以正常检索

### 测试6：向量维度验证
- ✅ Embedding 生成的向量维度为 1024
- ✅ 向量值范围验证

## 环境要求

### 必需服务

1. **Redis**（默认端口 36379；如需隔离测试环境也可临时映射到 6379）
   ```bash
   docker run -d --name redis-test -p 36379:6379 redis:7-alpine
   ```

2. **Milvus**（端口 19530）
   ```bash
   docker run -d --name milvus-standalone \
     -p 19530:19530 \
     -p 9091:9091 \
     -e ETCD_USE_EMBED=true \
     -e COMMON_STORAGETYPE=local \
     milvusdb/milvus:v2.3.3
   ```

3. **MySQL**（端口 3306）
   ```bash
   # 使用项目的 docker-compose.yml
   docker compose up -d mysql
   ```

4. **DashScope API Key**（千问 Embedding API）
   ```bash
   export DASHSCOPE_API_KEY=your_api_key_here
   ```

### 配置文件

创建 `src/test/resources/application-test.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/governance?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: root

  data:
    redis:
      host: localhost
      port: 36379

  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      embedding:
        options:
          model: text-embedding-v3

milvus:
  host: localhost
  port: 19530
  collection:
    name: user_memory_vectors_v3_test
    dimension: 1024
    index-type: IVF_FLAT
    metric-type: L2

memory:
  redis:
    dedup:
      enabled: true
```

## 运行测试

### 方式1：运行所有集成测试

```bash
cd vector-memory-service

# 启动所有依赖服务
docker compose up -d

# 设置 API Key
export DASHSCOPE_API_KEY=your_api_key_here

# 运行测试（移除 @Disabled 注解后）
mvn test -Dtest=VectorMemoryEndToEndTest
```

### 方式2：运行单个测试方法

```bash
# 运行特定测试
mvn test -Dtest=VectorMemoryEndToEndTest#testMemoryExtractionFlow
mvn test -Dtest=VectorMemoryEndToEndTest#testMemoryRetrievalFlow
mvn test -Dtest=VectorMemoryEndToEndTest#testMigrationScriptDryRun
```

### 方式3：在 IDE 中运行

1. 启动所有依赖服务（Redis、Milvus、MySQL）
2. 设置环境变量 `DASHSCOPE_API_KEY`
3. 移除 `@Disabled` 注解
4. 右键点击测试类或测试方法 → Run

## 注意事项

### 1. @Disabled 注解

测试类默认带有 `@Disabled` 注解，原因：

- 需要真实的 Redis 和 Milvus 连接
- 需要 DashScope API Key（会产生费用）
- CI/CD 环境中可能不具备这些条件

**本地运行时**，请移除 `@Disabled` 注解：

```java
// @Disabled("需要真实的 Redis 和 Milvus 环境，CI/CD 环境中跳过")
class VectorMemoryEndToEndTest {
    // ...
}
```

### 2. 测试顺序

测试使用 `@Order` 注解指定执行顺序：

1. 测试1 → 测试2 → 测试3：验证基本流程
2. 测试4 → 测试5：验证迁移脚本
3. 测试6：独立的维度验证

**建议按顺序运行**，因为某些测试依赖前置数据。

### 3. 数据清理

- 每个测试前后都会自动清理测试数据（`@BeforeEach` / `@AfterEach`）
- 清理范围：MySQL、Milvus、Redis
- 如果测试中断，可能需要手动清理残留数据

### 4. API 费用

- 每次生成 Embedding 会调用千问 API（约 0.0007 元/千 tokens）
- 完整测试套件约生成 10-20 次 Embedding
- 预计费用：< 0.1 元

### 5. 性能考虑

- 完整测试套件运行时间：约 30-60 秒
- Milvus 向量插入和查询需要时间
- Redis 操作通常很快（< 10ms）

## 故障排查

### 问题1：Redis 连接失败

```
错误: Redis 连接失败，测试将被跳过
```

**解决方案**：
```bash
# 检查 Redis 是否运行
docker ps | grep redis

# 启动 Redis
docker run -d --name redis-test -p 36379:6379 redis:7-alpine

# 测试连接
redis-cli ping
```

### 问题2：Milvus 连接失败

```
错误: Failed to connect to Milvus
```

**解决方案**：
```bash
# 检查 Milvus 是否运行
docker ps | grep milvus

# 启动 Milvus
docker run -d --name milvus-standalone \
  -p 19530:19530 \
  milvusdb/milvus:v2.3.3

# 等待 Milvus 启动（约 30 秒）
sleep 30
```

### 问题3：DashScope API Key 未设置

```
错误: DASHSCOPE_API_KEY is not set
```

**解决方案**：
```bash
# 设置环境变量
export DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxx

# 或在 application-test.yml 中配置
spring:
  ai:
    dashscope:
      api-key: sk-xxxxxxxxxxxxx
```

### 问题4：向量维度不匹配

```
错误: 向量维度不匹配: expected=1024, actual=512
```

**解决方案**：
- 检查 `application-test.yml` 中的模型配置
- 确保使用 `text-embedding-v3`（1024 维）
- 清理旧的 Milvus collection

### 问题5：测试数据残留

```
错误: 记忆已存在（Redis去重）
```

**解决方案**：
```bash
# 手动清理 Redis
redis-cli
> KEYS ai:memory:*
> DEL ai:memory:hash:test-group-e2e-001
> DEL ai:memory:recent:test-group-e2e-001

# 手动清理 MySQL
mysql -u root -p
> USE governance;
> DELETE FROM b_ai_group_memory WHERE group_id = 'test-group-e2e-001';
```

## 测试报告示例

```
=== 测试1：记忆提取流程 ===
保存记忆到系统...
验证 MySQL 记录...
✓ MySQL 验证通过 - id: 12345, embeddingId: abc-def-ghi
验证 Redis 去重索引...
✓ Redis 去重索引验证通过
验证 Redis 最近记忆缓存...
✓ Redis 最近记忆缓存验证通过 - 缓存数量: 1
=== 测试1完成：记忆提取流程验证通过 ===

=== 测试2：记忆检索流程 ===
测试 Redis 缓存命中...
✓ 检索到 1 条记忆
测试 Milvus 相似度搜索...
✓ 相似度搜索返回 1 条记忆
  - 记忆: 用户喜欢清淡饮食，不吃辛辣食物, 相似度: 0.85, 来源: RECENT_CACHE
=== 测试2完成：记忆检索流程验证通过 ===

=== 测试5：迁移脚本（实际迁移）===
准备测试数据（embedding_id = NULL）...
✓ 插入待迁移记忆 - id: 67890
运行迁移脚本（实际迁移）...
迁移统计: MigrationStats{total=1, migrated=1, skipped=0, failed=0}
✓ MySQL 验证通过 - embedding_id: xyz-123-456
✓ Redis 索引验证通过
✓ 记忆检索验证通过
=== 测试5完成：迁移脚本（实际迁移）验证通过 ===

Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

## 扩展测试

### 添加新测试场景

```java
@Test
@Order(7)
@DisplayName("测试并发写入场景")
void testConcurrentWrites() {
    // 实现并发写入测试
}
```

### 性能测试

```java
@Test
@Order(8)
@DisplayName("测试批量迁移性能")
void testMigrationPerformance() {
    // 插入 1000 条待迁移记忆
    // 测量迁移时间
    // 验证吞吐量
}
```

## 相关文档

- [向量记忆改造实施计划](../../../../docs/superpowers/plans/2026-05-18-vector-memory-implementation.md)
- [RedisMemoryIndexService 单元测试](../service/RedisMemoryIndexServiceTest.java)
- [VectorMemoryMigrationScript 源码](../../../main/java/com/lx/governance/vectormemory/migration/VectorMemoryMigrationScript.java)

## 联系方式

如有问题，请联系：
- 作者：LeJinBo
- 日期：2026-05-18
