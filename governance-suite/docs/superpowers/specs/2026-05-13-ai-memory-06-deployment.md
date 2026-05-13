# AI 记忆功能设计文档 - 第六部分：部署和测试策略

## 文档信息

- **创建日期**: 2026-05-13
- **项目**: governance-suite
- **功能**: AI 聊天用户记忆功能

## 一、部署架构

### 1.1 服务部署拓扑

```
┌─────────────────────────────────────────────────────────────┐
│                        负载均衡层                             │
│                     (可选，生产环境)                          │
└────────────────────────┬────────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         ↓               ↓               ↓
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│ ai-chat     │  │ ai-chat     │  │ ai-chat     │
│ service     │  │ service     │  │ service     │
│ (8103)      │  │ (8103)      │  │ (8103)      │
└──────┬──────┘  └──────┬──────┘  └──────┬──────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
         ┌──────────────┼──────────────┐
         ↓              ↓              ↓
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│user-profile │  │vector-memory│  │vector-memory│
│ service     │  │ service     │  │ service     │
│ (8101)      │  │ (8102)      │  │ (8102)      │
└──────┬──────┘  └──────┬──────┘  └──────┬──────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
         ┌──────────────┼──────────────┐
         ↓              ↓              ↓
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│   MySQL     │  │  RabbitMQ   │  │   Milvus    │
│   (3306)    │  │   (5672)    │  │  (19530)    │
└─────────────┘  └─────────────┘  └─────────────┘
         │              │              │
         └──────────────┼──────────────┘
                        │
         ┌──────────────┼──────────────┐
         ↓              ↓              ↓
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│   Redis     │  │   Nacos     │  │   etcd      │
│  (36379)    │  │   (8848)    │  │   (2379)    │
└─────────────┘  └─────────────┘  └─────────────┘
```

---

## 二、部署步骤

### 2.1 基础设施部署

**步骤 1：启动 Docker Compose**

```bash
# 进入项目根目录
cd /Users/lejinbo/LLM/code-snippets/governance-suite

# 启动所有基础设施
docker compose up -d

# 等待服务就绪（约 2-3 分钟）
docker compose ps

# 验证服务状态
docker compose ps | grep "Up"
```

**步骤 2：验证基础设施**

```bash
# 验证 MySQL
mysql -h 127.0.0.1 -P 3306 -u root -proot -e "SHOW DATABASES;"

# 验证 Redis
redis-cli -p 36379 ping

# 验证 RabbitMQ
curl http://localhost:15672/api/overview -u guest:guest

# 验证 Nacos
curl http://localhost:8848/nacos/v1/console/health/liveness

# 验证 Milvus
curl http://localhost:9091/healthz
```

**步骤 3：初始化数据库**

```bash
# 数据库初始化脚本会在 MySQL 启动时自动执行
# 验证表是否创建成功
mysql -h 127.0.0.1 -P 3306 -u root -proot governance -e "SHOW TABLES LIKE 'b_ai%';"
```

---

### 2.2 应用服务部署

**步骤 1：构建所有服务**

```bash
# 在项目根目录执行
mvn -T 1C clean package -DskipTests

# 验证构建结果
ls -lh user-profile-service/target/*.jar
ls -lh vector-memory-service/target/*.jar
ls -lh ai-chat-service/target/*.jar
```

**步骤 2：配置环境变量**

```bash
# 创建 .env 文件
cat > .env << 'EOF'
# 阿里云通义千问 API Key
export DASHSCOPE_API_KEY=your-dashscope-api-key

# DeepSeek API Key
export DEEPSEEK_API_KEY=your-deepseek-api-key

# Dify API Key
export DIFY_API_KEY=your-dify-api-key
EOF

# 加载环境变量
source .env
```

**步骤 3：启动服务**

```bash
# 使用启动脚本
chmod +x start-ai-services.sh
./start-ai-services.sh

# 或手动启动（每个服务在独立终端）
# 终端 1
mvn -q -pl user-profile-service spring-boot:run

# 终端 2
mvn -q -pl vector-memory-service spring-boot:run

# 终端 3
mvn -q -pl ai-chat-service spring-boot:run
```

**步骤 4：验证服务启动**

```bash
# 检查服务注册到 Nacos
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=user-profile-service
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=vector-memory-service
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=ai-chat-service

# 检查健康状态
curl http://localhost:8101/actuator/health
curl http://localhost:8102/actuator/health
curl http://localhost:8103/actuator/health
```

---

## 三、测试策略

### 3.1 单元测试

**user-profile-service 单元测试**

```java
@SpringBootTest
class UserProfileServiceTest {
    
    @Autowired
    private UserProfileService userProfileService;
    
    @Test
    void testBuildUserProfile() {
        UserProfileDTO profile = userProfileService.buildUserProfile("user123");
        assertNotNull(profile);
        assertNotNull(profile.getBasicInfo());
    }
    
    @Test
    void testCacheWorks() {
        // 第一次调用
        long start1 = System.currentTimeMillis();
        userProfileService.buildUserProfile("user123");
        long time1 = System.currentTimeMillis() - start1;
        
        // 第二次调用（应该从缓存读取）
        long start2 = System.currentTimeMillis();
        userProfileService.buildUserProfile("user123");
        long time2 = System.currentTimeMillis() - start2;
        
        assertTrue(time2 < time1, "缓存应该更快");
    }
}
```

**vector-memory-service 单元测试**

```java
@SpringBootTest
class VectorStoreServiceTest {
    
    @Autowired
    private EmbeddingService embeddingService;
    
    @Autowired
    private VectorStoreService vectorStoreService;
    
    @Test
    void testEmbeddingGeneration() {
        float[] embedding = embeddingService.generateEmbedding("测试文本");
        assertNotNull(embedding);
        assertEquals(1024, embedding.length);
    }
    
    @Test
    void testVectorSearch() {
        List<Document> results = vectorStoreService.searchGroupMemories(
            "胃部不适", "user123", "group456", 5
        );
        assertNotNull(results);
        assertTrue(results.size() <= 5);
    }
}
```

---

### 3.2 集成测试

**端到端聊天流程测试**

```bash
#!/bin/bash

echo "=== AI 记忆功能集成测试 ==="

API_URL="http://localhost:8103/api/ai/chat"

# 测试 1: 发送第一条消息
echo "测试 1: 发送第一条消息..."
RESPONSE1=$(curl -s -X POST "${API_URL}/send" \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "test_user_001",
    "groupId": "test_group_001",
    "message": "我最近胃部不太舒服，有什么建议吗？",
    "role": "doctor"
  }')

echo "$RESPONSE1" | jq .

# 提取 conversationId
CONVERSATION_ID=$(echo "$RESPONSE1" | jq -r '.data.conversationId')
echo "会话ID: $CONVERSATION_ID"

# 等待记忆提取完成
echo "等待记忆提取..."
sleep 10

# 测试 2: 发送第二条消息（应该能记住上下文）
echo "测试 2: 发送第二条消息..."
RESPONSE2=$(curl -s -X POST "${API_URL}/send" \
  -H "Content-Type: application/json" \
  -d "{
    \"customerId\": \"test_user_001\",
    \"groupId\": \"test_group_001\",
    \"message\": \"需要注意什么饮食禁忌吗？\",
    \"conversationId\": \"$CONVERSATION_ID\",
    \"role\": \"doctor\"
  }")

echo "$RESPONSE2" | jq .

# 测试 3: 查询聊天历史
echo "测试 3: 查询聊天历史..."
curl -s -X GET "${API_URL}/history?customerId=test_user_001&groupId=test_group_001" | jq .

# 测试 4: 验证记忆是否保存
echo "测试 4: 验证记忆是否保存..."
curl -s -X GET "http://localhost:8102/api/memory/group?customerId=test_user_001&groupId=test_group_001" | jq .

echo "=== 测试完成 ==="
```

---

### 3.3 性能测试

**使用 Apache Bench 进行压力测试**

```bash
#!/bin/bash

echo "=== 性能测试 ==="

# 准备测试数据
cat > test_payload.json << 'EOF'
{
  "customerId": "perf_test_user",
  "groupId": "perf_test_group",
  "message": "性能测试消息",
  "role": "doctor"
}
EOF

# 测试 1: 100 个并发请求
echo "测试 1: 100 个并发请求..."
ab -n 100 -c 10 -p test_payload.json -T application/json \
  http://localhost:8103/api/ai/chat/send

# 测试 2: 500 个并发请求
echo "测试 2: 500 个并发请求..."
ab -n 500 -c 50 -p test_payload.json -T application/json \
  http://localhost:8103/api/ai/chat/send

# 清理
rm test_payload.json

echo "=== 性能测试完成 ==="
```

**使用 JMeter 进行负载测试**

创建 `jmeter/ai-chat-load-test.jmx`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2">
  <hashTree>
    <TestPlan>
      <stringProp name="TestPlan.comments">AI 聊天负载测试</stringProp>
      <boolProp name="TestPlan.functional_mode">false</boolProp>
      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
    </TestPlan>
    <hashTree>
      <ThreadGroup>
        <stringProp name="ThreadGroup.num_threads">50</stringProp>
        <stringProp name="ThreadGroup.ramp_time">10</stringProp>
        <stringProp name="ThreadGroup.duration">60</stringProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
      </ThreadGroup>
      <hashTree>
        <HTTPSamplerProxy>
          <stringProp name="HTTPSampler.domain">localhost</stringProp>
          <stringProp name="HTTPSampler.port">8103</stringProp>
          <stringProp name="HTTPSampler.path">/api/ai/chat/send</stringProp>
          <stringProp name="HTTPSampler.method">POST</stringProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
          <elementProp name="HTTPsampler.Arguments">
            <collectionProp name="Arguments.arguments">
              <elementProp name="" elementType="HTTPArgument">
                <stringProp name="Argument.value">{
                  "customerId": "load_test_${__threadNum}",
                  "groupId": "load_test_group",
                  "message": "负载测试消息 ${__time}",
                  "role": "doctor"
                }</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
        </HTTPSamplerProxy>
      </hashTree>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
```

运行 JMeter 测试：

```bash
jmeter -n -t jmeter/ai-chat-load-test.jmx -l results.jtl -e -o report/
```

---

## 四、监控和告警

### 4.1 Prometheus 监控配置

**prometheus.yml**

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'user-profile-service'
    static_configs:
      - targets: ['localhost:8101']
    metrics_path: '/actuator/prometheus'
  
  - job_name: 'vector-memory-service'
    static_configs:
      - targets: ['localhost:8102']
    metrics_path: '/actuator/prometheus'
  
  - job_name: 'ai-chat-service'
    static_configs:
      - targets: ['localhost:8103']
    metrics_path: '/actuator/prometheus'
```

### 4.2 Grafana 仪表板

**关键监控指标**

1. **应用层指标**
   - 接口响应时间（P50, P95, P99）
   - 接口成功率
   - QPS（每秒请求数）
   - 错误率

2. **消息队列指标**
   - 队列深度
   - 消息消费速率
   - 消息处理延迟

3. **向量数据库指标**
   - Milvus 查询延迟
   - 向量检索成功率
   - Collection 大小

4. **缓存指标**
   - Redis 命中率
   - 缓存大小
   - 缓存过期率

---

## 五、故障排查

### 5.1 常见问题和解决方案

| 问题 | 可能原因 | 排查步骤 | 解决方案 |
|------|---------|---------|---------|
| 服务无法启动 | 端口被占用 | `lsof -i :8101` | 停止占用端口的进程 |
| Milvus 连接失败 | Milvus 未启动 | `docker compose ps milvus` | `docker compose up -d milvus` |
| 消息队列无响应 | 队列未创建 | 检查 RabbitMQ 管理界面 | 重启服务自动创建队列 |
| 向量检索无结果 | Collection 未创建 | 检查 Milvus 日志 | 重启 vector-memory-service |
| API Key 无效 | 环境变量未设置 | `echo $DASHSCOPE_API_KEY` | 设置正确的环境变量 |
| 缓存不生效 | Redis 连接失败 | `redis-cli -p 36379 ping` | 检查 Redis 配置 |

### 5.2 日志查看

```bash
# 查看实时日志
tail -f logs/user-profile-service.log
tail -f logs/vector-memory-service.log
tail -f logs/ai-chat-service.log

# 搜索错误日志
grep -i error logs/*.log

# 搜索特定 traceId 的日志
grep "trace-abc123" logs/*.log
```

### 5.3 数据库检查

```bash
# 检查记忆数据
mysql -h 127.0.0.1 -P 3306 -u root -proot governance << 'EOF'
SELECT COUNT(*) FROM b_ai_group_memory WHERE is_deleted = 0;
SELECT COUNT(*) FROM b_ai_user_memory WHERE is_deleted = 0;
SELECT COUNT(*) FROM b_ai_chat_history WHERE is_deleted = 0;
EOF

# 检查最近的聊天记录
mysql -h 127.0.0.1 -P 3306 -u root -proot governance << 'EOF'
SELECT id, customer_id, group_id, LEFT(user_message, 50) as message, create_time 
FROM b_ai_chat_history 
ORDER BY create_time DESC 
LIMIT 10;
EOF
```

---

## 六、回滚策略

### 6.1 服务回滚

```bash
# 停止当前版本
./stop-ai-services.sh

# 切换到上一个版本
git checkout <previous-version-tag>

# 重新构建
mvn clean package -DskipTests

# 启动服务
./start-ai-services.sh
```

### 6.2 数据库回滚

```bash
# 备份当前数据
mysqldump -h 127.0.0.1 -P 3306 -u root -proot governance \
  b_ai_group_memory b_ai_user_memory b_ai_chat_history \
  > backup_$(date +%Y%m%d_%H%M%S).sql

# 如需回滚，恢复备份
mysql -h 127.0.0.1 -P 3306 -u root -proot governance < backup_20260513_100000.sql
```

---

## 七、生产环境部署建议

### 7.1 高可用部署

1. **服务多实例部署**
   - 每个服务至少 2 个实例
   - 使用 Nginx 或 Spring Cloud Gateway 负载均衡

2. **数据库高可用**
   - MySQL 主从复制
   - Redis 哨兵模式或集群模式
   - RabbitMQ 镜像队列

3. **向量数据库高可用**
   - Milvus 集群部署
   - etcd 集群（至少 3 节点）
   - MinIO 分布式部署

### 7.2 安全加固

1. **API 安全**
   - 启用 HTTPS
   - 添加 API 网关认证
   - 限流和防刷

2. **数据安全**
   - 敏感数据加密存储
   - 定期备份
   - 访问控制

3. **网络安全**
   - 内网隔离
   - 防火墙规则
   - VPN 访问

---

## 八、总结

本文档详细说明了 AI 记忆功能的部署和测试策略：

1. **部署架构**：服务拓扑和部署方案
2. **部署步骤**：从基础设施到应用服务的完整部署流程
3. **测试策略**：单元测试、集成测试、性能测试
4. **监控和告警**：Prometheus + Grafana 监控方案
5. **故障排查**：常见问题和解决方案
6. **回滚策略**：服务和数据库回滚方案
7. **生产环境建议**：高可用和安全加固

至此，AI 记忆功能的完整设计文档已编写完成。
