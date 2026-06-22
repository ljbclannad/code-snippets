package com.lx.governance.streamdemo.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.streamdemo.service.MarkdownScriptService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class MarkdownWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MarkdownScriptService markdownScriptService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public MarkdownWebSocketHandler(MarkdownScriptService markdownScriptService) {
        this.markdownScriptService = markdownScriptService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.sendMessage(toMessage(Map.of(
            "type", "connected",
            "transport", "websocket",
            "message", "WebSocket connection established"
        )));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode root = objectMapper.readTree(message.getPayload());
        String prompt = root.path("prompt").asText("请演示 WebSocket Markdown");
        List<String> segments = markdownScriptService.buildSegments(prompt);

        executorService.execute(() -> {
            try {
                for (int i = 0; i < segments.size() && session.isOpen(); i++) {
                    session.sendMessage(toMessage(Map.of(
                        "type", "delta",
                        "index", i,
                        "content", segments.get(i)
                    )));
                    TimeUnit.MILLISECONDS.sleep(450);
                }

                if (session.isOpen()) {
                    session.sendMessage(toMessage(Map.of(
                        "type", "done",
                        "message", "WebSocket stream completed"
                    )));
                }
            } catch (Exception ex) {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(toMessage(Map.of(
                            "type", "error",
                            "message", ex.getMessage()
                        )));
                        session.close(CloseStatus.SERVER_ERROR);
                    }
                } catch (Exception ignored) {
                    // Ignore close failures during demo streaming.
                }
            }
        });
    }

    private TextMessage toMessage(Map<String, Object> payload) throws Exception {
        return new TextMessage(objectMapper.writeValueAsString(payload));
    }
}
