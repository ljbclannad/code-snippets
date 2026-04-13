package com.example.mcp;

import java.util.Arrays;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/mcp")
@Profile("mcp-client")
public class WeatherMcpClientController {

    private final ChatClient chatClient;

    private final List<ToolCallbackProvider> toolCallbackProviders;

    public WeatherMcpClientController(
            @Qualifier("DeepSeekChatClient") ChatClient chatClient,
            List<ToolCallbackProvider> toolCallbackProviders) {
        this.chatClient = chatClient;
        this.toolCallbackProviders = toolCallbackProviders;
    }

    @GetMapping("/weather")
    public String weather(@RequestParam(defaultValue = "杭州") String city) {
        return chatClient.prompt()
                .system("你可以调用远端 MCP server 暴露的 get_weather 工具获取天气。工具返回的是 demo 数据，回答时说明这是 MCP demo 数据。")
                .user("查询 " + city + " 今天的天气，并给出穿衣建议。")
                .toolCallbacks(mcpToolCallbacks())
                .call()
                .content();
    }

    @GetMapping("/tools")
    public List<String> tools() {
        return Arrays.stream(mcpToolCallbacks())
                .map(toolCallback -> toolCallback.getToolDefinition().name())
                .toList();
    }

    private ToolCallback[] mcpToolCallbacks() {
        return toolCallbackProviders.stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .toArray(ToolCallback[]::new);
    }
}
