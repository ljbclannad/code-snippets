package com.lx.governance.streamdemo.service;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class MarkdownScriptService {

    public List<String> buildSegments(String prompt) {
        String normalizedPrompt = prompt == null || prompt.isBlank() ? "演示一个流式 Markdown 输出" : prompt.trim();
        String lowerPrompt = normalizedPrompt.toLowerCase(Locale.ROOT);
        String title = lowerPrompt.contains("websocket") ? "WebSocket 输出示例" : "SSE 输出示例";

        return List.of(
            "# " + title + "\n\n",
            "## 用户请求\n\n",
            "> " + normalizedPrompt + "\n\n",
            "## 实时返回的 Markdown 能力\n\n",
            "- 分段输出文本\n" +
                "- 前端边收边渲染\n" +
                "- 支持代码块、列表、引用和表格\n\n",
            "## 代码片段\n\n",
            "```java\n" +
                "@GetMapping(path = \"/api/demo/sse\", produces = MediaType.TEXT_EVENT_STREAM_VALUE)\n" +
                "public SseEmitter stream(@RequestParam String prompt) { ... }\n" +
                "```\n\n",
            "## 状态说明\n\n",
            "| 阶段 | 说明 |\n" +
                "| --- | --- |\n" +
                "| connected | 建立连接 |\n" +
                "| delta | 返回 Markdown 片段 |\n" +
                "| done | 流式结束 |\n\n",
            "## 小结\n\n",
            "这个 demo 适合拿来说明：**后端如何推流，前端如何把增量文本实时渲染为 Markdown 页面。**\n"
        );
    }
}
