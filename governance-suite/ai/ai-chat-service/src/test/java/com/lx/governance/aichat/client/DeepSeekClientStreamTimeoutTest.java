package com.lx.governance.aichat.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyList;
import org.springframework.test.util.ReflectionTestUtils;
import com.lx.governance.aichat.service.DeepSeekIntentAnalyzer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.context.ContextBudgetManager;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.StreamEvent;
import com.lx.governance.aichat.monitor.TokenMonitor;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import reactor.core.publisher.Flux;

class DeepSeekClientStreamTimeoutTest {

    @Test
    void intentDeadlineCancelsEvenContinuouslyProducingModelAndReturnsFallback() {
        var streamingModel = mock(StreamingChatModel.class);
        var budget = mock(ContextBudgetManager.class);
        when(budget.buildBudgetedSystemPrompt(any(), any(), anyList(), any(), any(), any()))
                .thenReturn("系统提示");
        var cancelled = new AtomicBoolean();
        var chunk = new ChatResponse(List.of(new Generation(new AssistantMessage("{"))));
        when(streamingModel.stream(any(Prompt.class))).thenReturn(
                Flux.interval(Duration.ofMillis(10)).map(index -> chunk)
                        .doOnCancel(() -> cancelled.set(true)));
        var client = new DeepSeekClient(mock(ChatModel.class), streamingModel, budget,
                mock(TokenMonitor.class), new ContextBudgetProperties());
        var analyzer = new DeepSeekIntentAnalyzer();
        ReflectionTestUtils.setField(analyzer, "deepSeekClient", client);
        var registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(analyzer, "metrics", new AiChatMetrics(registry));
        var result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> analyzer.analyzeIntentWithTimeout("测试", List.of(), 100));
        assertEquals("unknown", result.getUserIntent());
        assertEquals(1.0, registry.get("ai.intent.analysis.fallbacks").counter().count());
        assertTrue(cancelled.get());
    }

    @Test
    void intentWithinDeadlineParsesCompleteModelReply() {
        var streamingModel = mock(StreamingChatModel.class);
        var budget = mock(ContextBudgetManager.class);
        when(budget.buildBudgetedSystemPrompt(any(), any(), anyList(), any(), any(), any()))
                .thenReturn("系统提示");
        var response = new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"currentTopic\":\"测试话题\",\"userIntent\":\"fact_query\",\"complexity\":\"simple\"}"))));
        when(streamingModel.stream(any(Prompt.class))).thenReturn(Flux.just(response));
        var client = new DeepSeekClient(mock(ChatModel.class), streamingModel, budget,
                mock(TokenMonitor.class), new ContextBudgetProperties());
        var analyzer = new DeepSeekIntentAnalyzer();
        ReflectionTestUtils.setField(analyzer, "deepSeekClient", client);
        ReflectionTestUtils.setField(analyzer, "metrics", mock(AiChatMetrics.class));
        var result = analyzer.analyzeIntentWithTimeout("测试", List.of(), 1000);
        assertEquals("测试话题", result.getCurrentTopic());
        assertEquals("fact_query", result.getUserIntent());
    }

    @Test
    void stalledModelAfterFirstChunkEmitsErrorAndCancelsUpstream() {
        var streamingModel = mock(StreamingChatModel.class);
        var budget = mock(ContextBudgetManager.class);
        var cancelled = new AtomicBoolean();
        var partial = new ChatResponse(List.of(new Generation(new AssistantMessage("部分内容"))));
        when(streamingModel.stream(any(Prompt.class))).thenReturn(Flux.just(partial)
                .concatWith(Flux.never()).doOnCancel(() -> cancelled.set(true)));
        when(budget.buildBudgetedSystemPrompt(any(), any(), any(LayeredMemories.class), any(), any(), any()))
                .thenReturn("系统提示");

        var request = new ChatRequest();
        request.setConversationId("conversation");
        request.setMessage("合成测试问题");

        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withPropertyValues("spring.ai.openai.read-timeout=100ms")
                .withBean(DeepSeekClient.class, () -> new DeepSeekClient(mock(ChatModel.class), streamingModel,
                        budget, mock(TokenMonitor.class), new ContextBudgetProperties()))
                .run(context -> {
                    var events = context.getBean(DeepSeekClient.class)
                            .chatStream(request, null, null, LayeredMemories.builder().build())
                            .collectList().block(Duration.ofSeconds(2));

                    assertEquals(List.of(StreamEvent.EventType.START, StreamEvent.EventType.CONTENT,
                            StreamEvent.EventType.ERROR), events.stream().map(StreamEvent::getType).toList());
                    assertEquals("部分内容", events.get(1).getContent());
                    assertTrue(cancelled.get());
                });
    }
}
