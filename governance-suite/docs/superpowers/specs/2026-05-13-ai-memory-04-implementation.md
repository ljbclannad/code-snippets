# AI 记忆功能设计文档 - 第四部分：技术实现细节

## 文档信息

- **创建日期**: 2026-05-13
- **项目**: governance-suite
- **功能**: AI 聊天用户记忆功能

## 一、依赖管理

### 1.1 父 pom.xml 更新

在项目根目录的 `pom.xml` 中添加：

```xml
<properties>
    <!-- 现有属性 -->
    <java.version>17</java.version>
    <spring-boot.version>3.2.5</spring-boot.version>
    <spring-cloud.version>2023.0.1</spring-cloud.version>
    
    <!-- 新增：Spring AI -->
    <spring-ai.version>1.0.0-M3</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <!-- 现有依赖管理 -->
        
        <!-- 新增：Spring AI BOM -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<repositories>
    <!-- 新增：Spring Milestones 仓库 -->
    <repository>
        <id>spring-milestones</id>
        <name>Spring Milestones</name>
        <url>https://repo.spring.io/milestone</url>
        <snapshots>
            <enabled>false</enabled>
        </snapshots>
    </repository>
</repositories>

<modules>
    <!-- 现有模块 -->
    <module>common</module>
    <module>gateway</module>
    <module>user-service</module>
    <module>order-service</module>
    <module>payment-service</module>
    <module>inventory-service</module>
    <module>outbox-consumer</module>
    
    <!-- 新增模块 -->
    <module>user-profile-service</module>
    <module>vector-memory-service</module>
    <module>ai-chat-service</module>
</modules>
```

---

### 1.2 各服务的 pom.xml

**user-profile-service/pom.xml**

```xml
<project>
    <parent>
        <groupId>com.lx.governance</groupId>
        <artifactId>governance-suite</artifactId>
        <version>1.0.0</version>
    </parent>
    
    <artifactId>user-profile-service</artifactId>
    <packaging>jar</packaging>
    
    <dependencies>
        <dependency>
            <groupId>com.lx.governance</groupId>
            <artifactId>common</artifactId>
            <version>${project.version}</version>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-cache</artifactId>
        </dependency>
        
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
        </dependency>
        
        <dependency>
            <groupId>mysql</groupId>
            <artifactId>mysql-connector-java</artifactId>
            <version>${mysql.version}</version>
            <scope>runtime</scope>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

**vector-memory-service/pom.xml**

```xml
<project>
    <parent>
        <groupId>com.lx.governance</groupId>
        <artifactId>governance-suite</artifactId>
        <version>1.0.0</version>
    </parent>
    
    <artifactId>vector-memory-service</artifactId>
    <packaging>jar</packaging>
    
    <dependencies>
        <dependency>
            <groupId>com.lx.governance</groupId>
            <artifactId>common</artifactId>
            <version>${project.version}</version>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
        
        <!-- Spring AI Core -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-core</artifactId>
        </dependency>
        
        <!-- Spring AI Milvus -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-milvus-store-spring-boot-starter</artifactId>
        </dependency>
        
        <!-- Spring AI Alibaba (通义千问) -->
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-starter</artifactId>
        </dependency>
        
        <!-- Spring AI OpenAI (DeepSeek 兼容) -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
        </dependency>
        
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
        </dependency>
        
        <dependency>
            <groupId>mysql</groupId>
            <artifactId>mysql-connector-java</artifactId>
            <version>${mysql.version}</version>
            <scope>runtime</scope>
        </dependency>
        
        <!-- Spring Retry -->
        <dependency>
            <groupId>org.springframework.retry</groupId>
            <artifactId>spring-retry</artifactId>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

**ai-chat-service/pom.xml**

```xml
<project>
    <parent>
        <groupId>com.lx.governance</groupId>
        <artifactId>governance-suite</artifactId>
        <version>1.0.0</version>
    </parent>
    
    <artifactId>ai-chat-service</artifactId>
    <packaging>jar</packaging>
    
    <dependencies>
        <dependency>
            <groupId>com.lx.governance</groupId>
            <artifactId>common</artifactId>
            <version>${project.version}</version>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.mybatis.spring.boot</groupId>
            <artifactId>mybatis-spring-boot-starter</artifactId>
        </dependency>
        
        <dependency>
            <groupId>mysql</groupId>
            <artifactId>mysql-connector-java</artifactId>
            <version>${mysql.version}</version>
            <scope>runtime</scope>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 二、Docker Compose 扩展

在 `docker-compose.yml` 中添加 Milvus 相关服务：

```yaml
services:
  # 现有服务...
  
  # Milvus 依赖的 etcd
  etcd:
    image: quay.io/coreos/etcd:v3.5.5
    container_name: gov-etcd
    environment:
      - ETCD_AUTO_COMPACTION_MODE=revision
      - ETCD_AUTO_COMPACTION_RETENTION=1000
      - ETCD_QUOTA_BACKEND_BYTES=4294967296
      - ETCD_SNAPSHOT_COUNT=50000
    volumes:
      - gov_etcd_data:/etcd
    command: etcd -advertise-client-urls=http://127.0.0.1:2379 -listen-client-urls http://0.0.0.0:2379 --data-dir /etcd
    healthcheck:
      test: ["CMD", "etcdctl", "endpoint", "health"]
      interval: 30s
      timeout: 10s
      retries: 3

  # Milvus 依赖的 MinIO
  minio:
    image: minio/minio:RELEASE.2023-03-20T20-16-18Z
    container_name: gov-minio
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - gov_minio_data:/minio_data
    command: minio server /minio_data --console-address ":9001"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 30s
      timeout: 10s
      retries: 3

  # Milvus 向量数据库
  milvus:
    image: milvusdb/milvus:v2.3.4
    container_name: gov-milvus
    environment:
      ETCD_ENDPOINTS: etcd:2379
      MINIO_ADDRESS: minio:9000
      MINIO_ACCESS_KEY_ID: minioadmin
      MINIO_SECRET_ACCESS_KEY: minioadmin
    ports:
      - "19530:19530"
      - "9091:9091"
    volumes:
      - gov_milvus_data:/var/lib/milvus
    depends_on:
      etcd:
        condition: service_healthy
      minio:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9091/healthz"]
      interval: 30s
      timeout: 10s
      retries: 3

volumes:
  # 现有 volumes...
  gov_etcd_data:
  gov_minio_data:
  gov_milvus_data:
```

---

## 三、环境变量配置

创建 `.env.example` 文件（不提交到 Git）：

```bash
# 阿里云通义千问 API Key
DASHSCOPE_API_KEY=your-dashscope-api-key-here

# DeepSeek API Key
DEEPSEEK_API_KEY=your-deepseek-api-key-here

# Dify API Key
DIFY_API_KEY=your-dify-api-key-here
```

在启动服务前，复制并配置：

```bash
cp .env.example .env
# 编辑 .env 文件，填入真实的 API Key
```

---

## 四、启动脚本

### 4.1 start-ai-services.sh

```bash
#!/bin/bash

echo "=== 启动 AI 记忆服务 ==="

# 检查环境变量
if [ -z "$DASHSCOPE_API_KEY" ]; then
    echo "错误: DASHSCOPE_API_KEY 未设置"
    echo "请先执行: export DASHSCOPE_API_KEY=your-key"
    exit 1
fi

if [ -z "$DEEPSEEK_API_KEY" ]; then
    echo "错误: DEEPSEEK_API_KEY 未设置"
    echo "请先执行: export DEEPSEEK_API_KEY=your-key"
    exit 1
fi

if [ -z "$DIFY_API_KEY" ]; then
    echo "错误: DIFY_API_KEY 未设置"
    echo "请先执行: export DIFY_API_KEY=your-key"
    exit 1
fi

# 检查基础设施
echo "检查基础设施状态..."
docker compose ps | grep -q "gov-mysql.*Up" || { echo "MySQL 未启动"; exit 1; }
docker compose ps | grep -q "gov-redis.*Up" || { echo "Redis 未启动"; exit 1; }
docker compose ps | grep -q "gov-rabbitmq.*Up" || { echo "RabbitMQ 未启动"; exit 1; }
docker compose ps | grep -q "gov-nacos.*Up" || { echo "Nacos 未启动"; exit 1; }
docker compose ps | grep -q "gov-milvus.*Up" || { echo "Milvus 未启动"; exit 1; }

echo "基础设施检查通过"

# 创建日志目录
mkdir -p logs

# 启动服务
echo "启动 user-profile-service (8101)..."
mvn -q -pl user-profile-service spring-boot:run > logs/user-profile-service.log 2>&1 &
echo $! > logs/user-profile-service.pid

sleep 10

echo "启动 vector-memory-service (8102)..."
mvn -q -pl vector-memory-service spring-boot:run > logs/vector-memory-service.log 2>&1 &
echo $! > logs/vector-memory-service.pid

sleep 10

echo "启动 ai-chat-service (8103)..."
mvn -q -pl ai-chat-service spring-boot:run > logs/ai-chat-service.log 2>&1 &
echo $! > logs/ai-chat-service.pid

echo ""
echo "=== 启动完成 ==="
echo "user-profile-service: http://localhost:8101"
echo "vector-memory-service: http://localhost:8102"
echo "ai-chat-service: http://localhost:8103"
echo ""
echo "查看日志: tail -f logs/*.log"
echo "停止服务: ./stop-ai-services.sh"
```

### 4.2 stop-ai-services.sh

```bash
#!/bin/bash

echo "=== 停止 AI 记忆服务 ==="

for service in user-profile-service vector-memory-service ai-chat-service; do
    if [ -f logs/${service}.pid ]; then
        pid=$(cat logs/${service}.pid)
        echo "停止 ${service} (PID: ${pid})..."
        kill $pid 2>/dev/null || echo "${service} 已停止"
        rm logs/${service}.pid
    fi
done

echo "=== 停止完成 ==="
```

---

## 五、测试脚本

### 5.1 test-ai-chat.sh

```bash
#!/bin/bash

API_URL="http://localhost:8103/api/ai/chat"

echo "=== 测试 AI 聊天功能 ==="

# 测试发送消息
echo "1. 发送聊天消息..."
curl -X POST "${API_URL}/send" \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "user123",
    "groupId": "group456",
    "message": "我最近胃部不太舒服，有什么建议吗？",
    "role": "doctor"
  }' | jq .

echo ""
echo "2. 查询聊天历史..."
curl -X GET "${API_URL}/history?customerId=user123&groupId=group456&page=1&size=10" | jq .
```

---

## 六、监控和日志

### 6.1 Actuator 配置

在各服务的 `application.yml` 中添加：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
```

### 6.2 日志配置

在各服务的 `src/main/resources/logback-spring.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <property name="LOG_PATH" value="logs"/>
    <property name="APP_NAME" value="${spring.application.name}"/>
    
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_PATH}/${APP_NAME}.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>${LOG_PATH}/${APP_NAME}.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <logger name="com.lx.governance" level="INFO"/>
    <logger name="org.springframework.ai" level="DEBUG"/>
    <logger name="io.milvus" level="INFO"/>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

---

## 七、总结

本文档详细说明了 AI 记忆功能的技术实现细节：

1. **依赖管理**：Maven 配置和 Spring AI 集成
2. **Docker Compose**：Milvus 及其依赖的部署
3. **环境变量**：API Key 配置
4. **启动脚本**：自动化启动和停止
5. **测试脚本**：功能验证
6. **监控和日志**：Actuator 和日志配置

下一步将编写第五、六部分的设计文档。
