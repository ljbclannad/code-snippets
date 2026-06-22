# Stream Markdown Demo

一个最小可运行的演示项目，用来说明：

- 后端如何通过 `SSE` 推送增量文本
- 后端如何通过 `WebSocket` 推送增量文本
- 前端如何在浏览器里把增量文本实时渲染成 `Markdown`

## 目录

```text
stream-markdown-demo/
├── src/main/java/.../controller/StreamingDemoController.java
├── src/main/java/.../websocket/MarkdownWebSocketHandler.java
├── src/main/java/.../service/MarkdownScriptService.java
└── src/main/resources/static/
    ├── index.html
    ├── app.css
    └── app.js
```

## 运行

在 `governance-suite` 根目录执行：

```bash
mvn -pl ai/stream-markdown-demo spring-boot:run
```

启动后打开：

[http://localhost:8200](http://localhost:8200)

## 演示点

1. 选择 `SSE`，页面会通过 `EventSource` 接收后端事件流。
2. 选择 `WebSocket`，页面会通过 `WebSocket` 建立双向连接。
3. 两种方式都会把 Markdown 分片逐步拼接，并实时渲染成标题、列表、代码块和表格。

## 接口

- `GET /api/demo/health`
- `GET /api/demo/sse?prompt=...`
- `GET /ws/markdown`
