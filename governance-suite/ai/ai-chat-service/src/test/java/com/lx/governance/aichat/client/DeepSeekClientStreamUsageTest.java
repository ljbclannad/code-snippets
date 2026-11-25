package com.lx.governance.aichat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.context.ContextBudgetManager;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.StreamEvent;
import com.lx.governance.aichat.monitor.TokenMonitor;
import com.sun.net.httpserver.HttpServer;

class DeepSeekClientStreamUsageTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emitsActualUsageInDoneEvenWhenFinalChunkHasNoContent(boolean usageOnly) throws Exception {
        var modelRequest = new AtomicReference<JsonNode>();
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                modelRequest.set(mapper.readTree(exchange.getRequestBody()));
                String finalChoices = usageOnly ? "[]" : "[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]";
                byte[] response = ("""
                        data: {"id":"test","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"测试回复"}}]}

                        data: {"id":"test","object":"chat.completion.chunk","choices":%s,"usage":{"prompt_tokens":120,"completion_tokens":45,"total_tokens":165}}

                        data: [DONE]

                        """).formatted(finalChoices).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var model = new OpenAiChatModel(new OpenAiApi("http://127.0.0.1:" + server.getAddress().getPort(), "test"));
            var budget = mock(ContextBudgetManager.class);
            when(budget.buildBudgetedSystemPrompt(any(), any(), any(LayeredMemories.class), any(), any(), any()))
                    .thenReturn("系统提示");
            var client = new DeepSeekClient(model, model, budget, mock(TokenMonitor.class), new ContextBudgetProperties());
            ReflectionTestUtils.setField(client, "model", "deepseek-v4-pro");
            var request = new ChatRequest();
            request.setConversationId("conversation");
            request.setMessage("测试问题");

            var events = client.chatStream(request, null, null, LayeredMemories.builder().build())
                    .collectList().block(Duration.ofSeconds(5));

            assertTrue(modelRequest.get().path("stream_options").path("include_usage").asBoolean());
            assertEquals(List.of(StreamEvent.EventType.START, StreamEvent.EventType.CONTENT, StreamEvent.EventType.DONE),
                    events.stream().map(StreamEvent::getType).toList());
            assertEquals("测试回复", events.get(1).getContent());
            assertEquals(new StreamEvent.TokenUsage(120, 45, 165), events.get(2).getTokenUsage());
        } finally {
            server.stop(0);
        }
    }
}
