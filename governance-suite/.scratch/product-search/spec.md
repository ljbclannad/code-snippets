# 商品搜索初版实施规格

## 已确认范围

用户确认：新增最小商品管理与 MySQL 到 Elasticsearch 同步链路；一条商品只有一个价格；买家搜索 API 无需登录且只返回上架商品，不做页面。标题分词、类目/品牌/价格过滤、相关性/价格排序和分页属于本次范围。查询理解、多路召回、个性化、聚合导航和 SPU/SKU 多规格不属于本次范围。

## 实施约定

- `product-service`（8085）维护商品事实和同事务 Outbox；`outbox-consumer` 可选启用商品发布器；`search-service`（8086）消费完整事实快照并查询 ES。
- 使用官方 `smartcn` 分析器，ES 8.11.0 插件与引擎版本一致；关键词各词项 AND 匹配。空关键词浏览上架商品。
- 商品字段：productId、title、categoryId、brandId、priceCents、status、version、updatedAt；ID 使用 ASCII 字母数字下划线和连字符。价格单位分，0–999999999999。
- 商品维护使用现有签名令牌，并以 PRODUCT_ADMIN_USER_IDS 白名单授权；默认空白名单拒绝所有维护用户。
- 搜索使用 q、categoryId、brandId、minPriceCents、maxPriceCents、sort、page、size；sort 为 RELEVANCE/PRICE_ASC/PRICE_DESC，默认 RELEVANCE，page 从 1 开始，size 默认 20、最大 100，结果窗口最多 10000。商品 ID 是次级排序键；并发更新下不保证跨页快照。
- 买家 API 强制 ON_SALE，拒绝 status 等未支持参数；ES 不可用或部分结果返回 503，不伪装空结果。
- 商品版本单调递增；ES external 版本阻止重复/旧事件覆盖；下架保留文档并过滤，避免旧消息复活。
- 发布检查 broker confirm 和返回消息；消费失败有限重试后死信；通过商品事实重新发布恢复。
- 新商品发布器默认禁用，保留现有订单/支付启动行为；不修改 common 模块或交易路径。

## 已确认测试边界

1. 买家搜索 HTTP：分词匹配、组合过滤、排序、分页、参数边界、只显示上架、服务故障。
2. 商品维护 HTTP：身份与白名单、创建/修改/上下架、并发版本冲突。
3. 变更同步：同事务、重复/乱序、投递失败/重试/死信以及恢复。

测试使用隔离资源，不运行会写入已有业务数据的历史验收脚本。按模块验证；遵守用户要求，不执行 Git、提交或发布。
