# 商品搜索初版

买家通过关键词搜索上架商品，并按类目、品牌、价格筛选，按相关性或价格排序分页。初版只提供 API；一条商品只有一个人民币价格，不建多规格、商品详情页、库存搜索、查询理解、多路召回或个性化排序。

## 数据与服务边界

```text
商品维护者 → gateway → product-service → MySQL 商品事实 + Outbox（同事务）
                                             ↓
                          outbox-consumer → RabbitMQ → search-service → ES
买家 → gateway → search-service → ES 搜索索引
```

- `product-service`：8085，商品事实的唯一写入方；商品 ID、类目 ID、品牌 ID 均为 1–64 位 ASCII 字母数字、下划线或连字符；标题最多 200 字符。
- `search-service`：8086，标题使用官方 `smartcn` 中文分析器，关键词使用 `match` 的 AND 语义；类目、品牌、上下架使用 keyword，价格使用 long。
- `outbox-consumer`：8090，商品发布器默认关闭；开启后读取 `product_db.t_product_outbox`。
- 新链路不改变订单后台检索、支付或库存交易路径；搜索价格是展示价格，不是下单价格校验依据。

## 启动

从仓库根目录操作。新环境先按主 README 准备 MySQL、RabbitMQ、Redis、Nacos 及现有 Outbox 所需的订单和支付数据库。

1. 执行商品数据库迁移（只新增商品库和表，不包含已有商品修改）：

```bash
mysql -h127.0.0.1 -P3306 -uroot -p < sql/migrations/20260918_01_product_catalog.sql
```

2. 构建带中文插件的 ES 8.11.0。这个叠加配置会重新创建主 compose 的 ES 容器，复用其原有数据卷；需要商品搜索时才使用。镜像构建需要能访问 Elastic 插件下载站点。

```bash
docker compose -f docker-compose.yml -f docker-compose.product-search.yml up -d --build elasticsearch
```

现有 `docker compose up -d` 不会自动安装插件。缺少 `smartcn` 时搜索服务启动失败，不静默退化为单字检索。可以先验证：

```bash
curl -s http://localhost:9200/_analyze -H 'Content-Type: application/json' \
  -d '{"analyzer":"smartcn","text":"男士夏季透气跑步鞋"}'
```

3. 配置环境变量并在不同终端启动服务。`AUTH_TOKEN_SECRET` 必须与用户服务和网关一致；`PRODUCT_ADMIN_USER_IDS` 是已注册用户的 ID 列表，不是角色名。

| 服务 | 环境变量 | 含义 |
| --- | --- | --- |
| 商品 | `PRODUCT_DB_URL` | 默认本机 MySQL 的 product_db，UTC 时间 |
| 商品 | `PRODUCT_DB_USERNAME`、`PRODUCT_DB_PASSWORD` | 商品库账号密码 |
| 商品 | `PRODUCT_ADMIN_USER_IDS` | 逗号分隔的维护者用户 ID；默认空，拒绝所有维护操作 |
| 商品/网关/用户 | `AUTH_TOKEN_SECRET` | 使用相同令牌签名配置 |
| 搜索 | `PRODUCT_SEARCH_ES_URL` | 默认 `http://localhost:9200` |
| 搜索 | `PRODUCT_SEARCH_INDEX` | 默认 `product-catalog-v1` |
| 搜索 | `PRODUCT_SEARCH_ES_USERNAME`、`PRODUCT_SEARCH_ES_PASSWORD` | 可选 ES 基本认证 |
| 发布器 | `PRODUCT_OUTBOX_ENABLED` | 设为 `true` 才启用商品发布器 |
| 发布器 | `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_USER`、`MYSQL_PASS` | 与商品服务指向同一个 product_db；也用于既有订单/支付发布器 |
| 搜索/发布器 | `RABBIT_HOST`、`RABBIT_PORT`、`RABBIT_USER`、`RABBIT_PASS` | 同一个 RabbitMQ |
| 商品/搜索 | `NACOS_ADDR`、`NACOS_REGISTER_ENABLED` | 服务发现地址、是否注册；新模块默认禁用 Nacos 远程配置 |
| 网关 | `SEARCH_SERVICE_URI`、`PRODUCT_SERVICE_URI` | 默认 Nacos `lb://` 路由；可覆盖为直连地址 |

```bash
mvn -f distributed/product-service/pom.xml spring-boot:run
mvn -f distributed/search-service/pom.xml spring-boot:run
PRODUCT_OUTBOX_ENABLED=true mvn -f distributed/outbox-consumer/pom.xml spring-boot:run
mvn -f distributed/gateway/pom.xml spring-boot:run
```

若 Outbox 已运行，需带新配置重启该进程，不要启动重复进程。搜索服务会在监听消息前创建并验证索引映射；已有索引不兼容时失败并保留原数据。默认本地索引为 1 分片、0 副本，不代表生产可用性配置。

## 商品维护接口

网关前缀 `/api/product`；直连商品服务省略此前缀。所有 `/admin/**` 请求均在商品服务验签并检查维护白名单，伪造角色或身份 Header 无效。匿名返回 401，非白名单用户返回 403。

| 方法与路径 | 行为 |
| --- | --- |
| `POST /admin/products` | 创建，返回 201 和 version=1；重复 ID 返回 409 |
| `GET /admin/products/{productId}` | 读取当前商品 |
| `PUT /admin/products/{productId}` | 全量更新，传 expectedVersion；并发冲突返回 409 |
| `GET /admin/products?afterId=0&limit=100` | 管理分页；limit 1–500，响应 nextAfterId/hasMore |
| `POST /admin/products/{productId}/republish` | 发布当前商品快照用于恢复；不改变商品版本 |

先通过用户服务登录获取维护者令牌，保存在 `PRODUCT_ADMIN_TOKEN` 环境变量中。示例：

```bash
curl -i http://localhost:8080/api/product/admin/products \
  -H "Authorization: Bearer ${PRODUCT_ADMIN_TOKEN}" -H 'Content-Type: application/json' \
  -d '{"productId":"shoe-demo-1","title":"男士夏季透气跑步鞋","categoryId":"shoes","brandId":"sport-a","priceCents":19900,"status":"ON_SALE"}'
```

修改使用相同业务字段、去掉 productId、添加 `expectedVersion`。`status` 仅接受 `ON_SALE` 和 `OFF_SALE`；价格为 0–999999999999 分的整数。品牌、类目仅保存 ID，不包含单独的品牌/类目管理系统。

## 买家搜索接口

`GET /api/search/products` 无需登录。直连搜索服务是 `GET /products`；网关只有精确的公开 GET 路径免登录，服务发现路径不属于公开契约。

```bash
curl -G http://localhost:8080/api/search/products \
  --data-urlencode 'q=跑步鞋 透气' \
  --data-urlencode 'categoryId=shoes' \
  --data-urlencode 'brandId=sport-a' \
  --data-urlencode 'minPriceCents=10000' \
  --data-urlencode 'maxPriceCents=30000' \
  --data-urlencode 'sort=PRICE_ASC' \
  --data-urlencode 'page=1' --data-urlencode 'size=20'
```

| 参数 | 约束 |
| --- | --- |
| `q` | 可省略或为空，浏览全部上架商品；去除首尾空白后最多 100 字符 |
| `categoryId`、`brandId` | 可选，精确且区分大小写匹配 |
| `minPriceCents`、`maxPriceCents` | 可选，包含边界，最低不能高于最高 |
| `sort` | `RELEVANCE`（默认）、`PRICE_ASC`、`PRICE_DESC` |
| `page` | 从 1 开始 |
| `size` | 默认 20，1–100；page × size 不得超过 10000 |

服务端强制过滤上架商品，不能传 `status` 扩大结果集；未知参数返回 400。相关性按 ES 得分降序，价格按指定方向排序，均以商品 ID 升序打破并列。空关键词按相关性排序时各商品得分相同，最终按商品 ID 排序。

响应使用 `ApiResponse`，`data` 含 `items`、`total`、`page`、`size`、`hasNext`；每项只包含商品 ID、标题、类目、品牌、价格。total 为匹配总数，超过窗口后不再提供下一页。分页不提供并发修改期间的跨请求快照。非法参数返回 400；ES 故障、超时或部分分片结果返回 503，不伪装成成功空列表。

## 一致性与恢复

商品事实和 Outbox 同事务提交。发布器检查 RabbitMQ confirm 且没有 returned 消息后标 SENT；失败按 outbox.max-retries（默认5）和 outbox.retry-delay-seconds（默认10秒）重试，耗尽标 DEAD。

消息由 `biz.events` 的 `product.changed` 路由进入持久队列 `q.product.search`；消费者默认最多尝试3次，间隔1秒、2秒，最终拒绝进入 `q.product.search.dead`。初版使用经典持久队列的 broker 死信机制，不承诺跨 broker 故障的死信转发强保证；MySQL 商品事实和受控重放是恢复依据。

ES 使用商品版本作为 `external` 版本；重复、旧版本冲突视为已处理，其他错误继续失败重试。下架保留文档，始终过滤 OFF_SALE，避免旧上架消息复活。索引异步更新，并有默认1秒刷新周期；下架或改价后不会承诺立即可见。搜索不参与交易，也不保证实时库存。

恢复步骤：确认 ES/MQ 正常 → 修复错误源数据 → 对受影响商品调用 republish → 等待索引刷新并核对搜索。无须修改商品版本来重放，也不会自动删除旧死信/DEAD记录。

全量重建：停止旧索引消费者，配置新的 PRODUCT_SEARCH_INDEX 并启动搜索服务创建新映射；使用维护分页依次获取商品并对每个商品调用 republish，追平所有事件后核对结果。初版是维护窗口内的重建，切换期间可能出现不完整列表；不提供双索引写入、别名无停机切换或跨服务快照。

可观察 `product.search.projection.total` 的 applied/stale/failed/invalid 标签，以及 [商品发布器指标与配置](outbox-consumer/README-PRODUCT-OUTBOX.md)。单实例发布器为初版运行方式；消息采用至少一次投递，不能假设没有重复。

## 验证

```bash
mvn -f distributed/product-service/pom.xml test
mvn -f distributed/search-service/pom.xml test
mvn -f distributed/outbox-consumer/pom.xml test
mvn -f distributed/gateway/pom.xml test
```

商品模块使用 H2 隔离验证事务与 HTTP；搜索默认测试使用模拟 ES HTTP 响应。真实 ES 测试需显式指定隔离实例地址，实例必须已安装 smartcn；测试创建随机索引，适合临时容器，测试结束统一销毁临时容器即可。

```bash
PRODUCT_SEARCH_TEST_ES_URL=http://localhost:19218 \
  mvn -f distributed/search-service/pom.xml -Dtest=ProductSearchElasticsearchIntegrationTest test
```

参考：[官方 Smart Chinese 插件](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-smartcn)、[ES 外部版本](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/docs-index_.html)。本项目实际使用并验证 ES 8.11.0，插件镜像固定为相同版本。

### 跨服务验收脚本

`sh/test/test-product-search.py` 仅在显式授权的隔离环境写入自己创建的测试商品，成功或失败后都会尝试下架。不会修改库存或创建订单。失败清理不成功时脚本会报告商品 ID，供人工检查。

先配置有效的维护者与非维护者令牌到 `TEST_ADMIN_TOKEN` 和 `TEST_NONADMIN_TOKEN`，再执行：

```bash
E2E_WRITE=1 TEST_PRODUCT_URL=http://localhost:8085 TEST_SEARCH_URL=http://localhost:8086 \
  python3 sh/test/test-product-search.py
```

也可将 URL 分别设置为网关的 `http://localhost:8080/api/product` 和 `http://localhost:8080/api/search`。令牌必须与目标环境签名配置一致，不能将真实凭证写入脚本。

### 2026-09-18 验收记录

- 商品服务 9、搜索服务 7、Outbox 5、网关 8，共 29 项测试通过，无失败、错误或跳过。搜索服务本轮显式连接带 smartcn 的临时 ES 8.11.0，因此包含真实检索测试；不设测试环境变量时，该集成测试会明确跳过。
- 真实隔离环境使用临时 MySQL、RabbitMQ 和 ES，以及三个服务进程。直连 HTTP 验证创建→Outbox→MQ→索引、中文关键词、金额过滤、分页边界、401/403、标题价格更新、版本冲突、重放与下架。
- 通过真实 MQ 重新投递旧版上架事件后，下架商品仍未出现；非法事件进入商品死信队列。网关路径与认证规则由模块测试验证，本轮真实链路使用直连服务。
- Docker 叠加配置 `config --quiet` 校验通过。没有对原有数据库应用迁移，没有启动或修改原有业务服务；测试进程和临时容器在验收后关闭。
- 本轮证明功能正确性，不包含容量、并发和性能基线验收。
