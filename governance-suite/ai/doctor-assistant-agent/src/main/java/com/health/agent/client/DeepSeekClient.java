package com.health.agent.client;

import com.health.agent.model.Message;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.lx.governance.agentplatform.model.PlatformCostScope;
import com.lx.governance.agentplatform.runtime.PlatformMetricsService;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Flux;

/**
 * DeepSeek API 客户端
 * 用于调用 DeepSeek API 进行 ReAct Agent 推理
 */
@Slf4j
@Component
public class DeepSeekClient {

    private final ChatModel chatModel;

    @Value("${agent.deepseek.model:deepseek-chat}")
    private String model;

    @Value("${agent.deepseek.temperature:0.7}")
    private double temperature;

    @Value("${agent.deepseek.max-tokens:2000}")
    private int maxTokens;

    @Value("${agent.deepseek.request-timeout:5s}")
    private Duration requestTimeout;

    @Value("${agent.deepseek.max-retries:2}")
    private int maxRetries;

    @Value("${agent.metrics.cost.prompt-price-per-1k-tokens:0}")
    private double promptPricePer1kTokens;

    @Value("${agent.metrics.cost.completion-price-per-1k-tokens:0}")
    private double completionPricePer1kTokens;

    private volatile ExecutorService requestExecutor;
    private PlatformMetricsService platformMetricsService;

    public DeepSeekClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Autowired(required = false)
    void setRequestExecutor(@Qualifier("deepSeekRequestExecutor") ExecutorService requestExecutor) {
        this.requestExecutor = requestExecutor;
    }

    @Autowired(required = false)
    void setPlatformMetricsService(PlatformMetricsService platformMetricsService) {
        this.platformMetricsService = platformMetricsService;
    }

    /**
     * 调用 DeepSeek API 进行对话
     *
     * @param systemPrompt System Prompt
     * @param messages     对话历史
     * @return DeepSeek 响应
     */
    public DeepSeekResponse chat(String systemPrompt, List<Message> messages) {
        long startTime = System.currentTimeMillis();

        log.info("调用 DeepSeek API, 消息数量: {}", messages.size());
        Prompt prompt = buildPrompt(systemPrompt, messages);

        RuntimeException lastException = null;
        int maxAttempts = maxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ChatResponse response = executeWithTimeout(prompt);
                return buildResponse(response, startTime);
            } catch (RuntimeException e) {
                lastException = e;
                log.warn("DeepSeek API 第{}次调用失败: {}", attempt, e.getMessage());
                if (attempt == maxAttempts) {
                    break;
                }
            }
        }
        long duration = System.currentTimeMillis() - startTime;
        log.error("DeepSeek API 调用失败, 耗时: {}ms", duration, lastException);
        throw lastException != null
            ? lastException
            : new RuntimeException("DeepSeek API 调用失败: 未知错误");
    }

    public DeepSeekResponse chatStream(
        String systemPrompt,
        List<Message> messages,
        Consumer<String> deltaSink
    ) {
        long startTime = System.currentTimeMillis();

        log.info("调用 DeepSeek Stream API, 消息数量: {}", messages.size());
        Prompt prompt = buildPrompt(systemPrompt, messages);

        RuntimeException lastException = null;
        int maxAttempts = maxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeStream(prompt, deltaSink, startTime);
            } catch (RuntimeException e) {
                lastException = e;
                log.warn("DeepSeek Stream API 第{}次调用失败: {}", attempt, e.getMessage());
                if (attempt == maxAttempts || !isRetryableStreamFailure(e)) {
                    break;
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.error("DeepSeek Stream API 调用失败, 耗时: {}ms", duration, lastException);
        throw lastException != null
            ? lastException
            : new RuntimeException("DeepSeek Stream API 调用失败: 未知错误");
    }

    private Prompt buildPrompt(String systemPrompt, List<Message> messages) {
        List<org.springframework.ai.chat.messages.Message> aiMessages = new ArrayList<>();
        aiMessages.add(new SystemMessage(systemPrompt));
        for (Message msg : messages) {
            if ("user".equals(msg.getRole())) {
                aiMessages.add(new UserMessage(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                aiMessages.add(new AssistantMessage(msg.getContent()));
            }
        }

        return new Prompt(
            aiMessages,
            OpenAiChatOptions.builder()
                .withModel(model)
                .withTemperature(temperature)
                .withMaxTokens(maxTokens)
                .build()
        );
    }

    private ChatResponse executeWithTimeout(Prompt prompt) {
        Future<ChatResponse> future;
        try {
            future = getRequestExecutor().submit(() -> chatModel.call(prompt));
        } catch (RejectedExecutionException e) {
            throw new RuntimeException("DeepSeek 请求线程池已饱和", e);
        }
        try {
            return future.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("DeepSeek API 调用超时: " + requestTimeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DeepSeek API 调用被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("DeepSeek API 调用失败: " + cause.getMessage(), cause);
        }
    }

    private DeepSeekResponse executeStream(Prompt prompt, Consumer<String> deltaSink, long startTime) {
        StringBuilder contentBuilder = new StringBuilder();
        List<ChatResponse> responses;
        StreamState streamState = new StreamState();
        try {
            Flux<ChatResponse> responseFlux = chatModel.stream(prompt)
                .timeout(requestTimeout)
                .doOnNext(response -> {
                    String delta = extractContent(response);
                    if (!delta.isEmpty()) {
                        streamState.markDeltaEmitted();
                        contentBuilder.append(delta);
                        if (deltaSink != null) {
                            deltaSink.accept(delta);
                        }
                    }
                });
            responses = responseFlux.collectList().block();
        } catch (RuntimeException e) {
            throw unwrapStreamException(e, streamState.deltaEmitted());
        }

        if (responses == null || responses.isEmpty()) {
            DeepSeekResponse response = new DeepSeekResponse();
            response.setContent(contentBuilder.toString());
            response.setDuration(System.currentTimeMillis() - startTime);
            return response;
        }

        ChatResponse lastResponse = responses.get(responses.size() - 1);
        DeepSeekResponse response = buildResponse(lastResponse, startTime);
        response.setContent(contentBuilder.toString());
        return response;
    }

    private DeepSeekResponse buildResponse(ChatResponse response, long startTime) {
        String content = extractContent(response);
        var metadata = response.getMetadata();
        var usage = metadata != null ? metadata.getUsage() : null;
        if (usage == null) {
            log.warn("DeepSeek API 未返回 token 使用信息");
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("DeepSeek API 调用完成, 耗时: {}ms, tokens: prompt={}, completion={}, total={}",
            duration,
            usage != null ? usage.getPromptTokens() : 0,
            usage != null ? usage.getGenerationTokens() : 0,
            usage != null ? usage.getTotalTokens() : 0);

        DeepSeekResponse deepSeekResponse = new DeepSeekResponse();
        deepSeekResponse.setContent(content);
        if (usage != null) {
            deepSeekResponse.setPromptTokens(usage.getPromptTokens().intValue());
            deepSeekResponse.setCompletionTokens(usage.getGenerationTokens().intValue());
            deepSeekResponse.setTotalTokens(usage.getTotalTokens().intValue());
        }
        deepSeekResponse.setDuration(duration);
        recordPlatformTokenCost(deepSeekResponse);
        return deepSeekResponse;
    }

    private void recordPlatformTokenCost(DeepSeekResponse response) {
        if (response == null || platformMetricsService == null) {
            return;
        }
        platformMetricsService.recordTokenCost(
            PlatformCostScope.AGENT_WORKFLOW,
            "doctor-assistant-agent",
            response.getPromptTokens(),
            response.getCompletionTokens(),
            response.getTotalTokens(),
            estimateCost(response.getPromptTokens(), response.getCompletionTokens())
        );
    }

    private double estimateCost(int promptTokens, int completionTokens) {
        return (promptTokens / 1000.0D) * Math.max(0D, promptPricePer1kTokens)
            + (completionTokens / 1000.0D) * Math.max(0D, completionPricePer1kTokens);
    }

    private String extractContent(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String content = response.getResult().getOutput().getContent();
        return content != null ? content : "";
    }

    private RuntimeException unwrapStreamException(RuntimeException exception, boolean deltaEmitted) {
        Throwable cause = exception.getCause();
        if (cause instanceof TimeoutException) {
            return new StreamExecutionException("DeepSeek API 调用超时: " + requestTimeout, cause, !deltaEmitted);
        }
        if (cause instanceof RuntimeException runtimeException) {
            return new StreamExecutionException(runtimeException.getMessage(), runtimeException, !deltaEmitted);
        }
        return new StreamExecutionException(exception.getMessage(), exception, !deltaEmitted);
    }

    private boolean isRetryableStreamFailure(RuntimeException exception) {
        return exception instanceof StreamExecutionException streamExecutionException
            && streamExecutionException.retryable();
    }

    private int maxAttempts() {
        return Math.max(1, maxRetries + 1);
    }

    private ExecutorService getRequestExecutor() {
        if (requestExecutor == null) {
            synchronized (this) {
                if (requestExecutor == null) {
                    requestExecutor = new ThreadPoolExecutor(
                        2,
                        4,
                        60000,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(16),
                        new RequestThreadFactory(),
                        (runnable, executor) -> {
                            throw new RejectedExecutionException("DeepSeek 请求线程池已饱和");
                        }
                    );
                }
            }
        }
        return requestExecutor;
    }

    @PreDestroy
    void shutdownExecutor() {
        if (requestExecutor != null) {
            requestExecutor.shutdownNow();
        }
    }

    private static class RequestThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "deepseek-request");
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class StreamState {
        private volatile boolean deltaEmitted;

        private void markDeltaEmitted() {
            deltaEmitted = true;
        }

        private boolean deltaEmitted() {
            return deltaEmitted;
        }
    }

    private static final class StreamExecutionException extends RuntimeException {
        private final boolean retryable;

        private StreamExecutionException(String message, Throwable cause, boolean retryable) {
            super(message, cause);
            this.retryable = retryable;
        }

        private boolean retryable() {
            return retryable;
        }
    }

    /**
     * DeepSeek API 响应
     */
    @Data
    public static class DeepSeekResponse {
        /**
         * 响应内容
         */
        private String content;

        /**
         * Prompt tokens
         */
        private int promptTokens;

        /**
         * Completion tokens
         */
        private int completionTokens;

        /**
         * Total tokens
         */
        private int totalTokens;

        /**
         * 调用耗时（毫秒）
         */
        private long duration;
    }
}
