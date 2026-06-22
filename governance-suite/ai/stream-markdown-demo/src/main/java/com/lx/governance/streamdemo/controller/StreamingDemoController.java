package com.lx.governance.streamdemo.controller;

import com.lx.governance.streamdemo.service.MarkdownScriptService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/demo")
public class StreamingDemoController {

    private final MarkdownScriptService markdownScriptService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public StreamingDemoController(MarkdownScriptService markdownScriptService) {
        this.markdownScriptService = markdownScriptService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "UP",
            "timestamp", Instant.now().toString(),
            "transports", List.of("sse", "websocket")
        );
    }

    @GetMapping(path = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "prompt", defaultValue = "请演示 SSE Markdown") String prompt) {
        SseEmitter emitter = new SseEmitter(0L);
        List<String> segments = markdownScriptService.buildSegments(prompt);

        executorService.execute(() -> {
            try {
                emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("transport", "sse", "message", "SSE connection established")));

                for (int i = 0; i < segments.size(); i++) {
                    emitter.send(SseEmitter.event()
                        .name("delta")
                        .data(Map.of(
                            "index", i,
                            "content", segments.get(i)
                        )));
                    TimeUnit.MILLISECONDS.sleep(450);
                }

                emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of("message", "SSE stream completed")));
                emitter.complete();
            } catch (IOException | InterruptedException ex) {
                Thread.currentThread().interrupt();
                emitter.completeWithError(ex);
            }
        });

        return emitter;
    }
}
