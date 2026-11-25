package com.lx.governance.aichat.client;

import com.lx.governance.aichat.config.RabbitMQConfig;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.MemoryRequestMessage;
import com.lx.governance.aichat.dto.MemoryResponseMessage;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.dto.UserProfileRequestMessage;
import com.lx.governance.aichat.dto.UserProfileResponseMessage;
import com.lx.governance.common.trace.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RpcReplyIsolationTest {
    @AfterEach
    void clearTrace() {
        Trace.clear();
    }

    @Test
    void replyQueuesBelongToOneInstanceAndDisappearOnDisconnect() {
        RabbitMQConfig first = new RabbitMQConfig();
        RabbitMQConfig second = new RabbitMQConfig();
        Queue profile = first.userProfileReplyQueue();
        Queue memory = first.vectorMemoryReplyQueue();
        assertNotEquals(profile.getName(), second.userProfileReplyQueue().getName());
        assertNotEquals(memory.getName(), second.vectorMemoryReplyQueue().getName());
        assertNotEquals(profile.getName(), memory.getName());
        for (Queue queue : List.of(profile, memory)) {
            assertFalse(queue.isDurable());
            assertTrue(queue.isExclusive());
            assertTrue(queue.isAutoDelete());
        }
    }

    @Test
    void profileRepliesCompleteOnlyTheOriginatingInstanceEvenWhenDeliveredOutOfOrder() throws Exception {
        Broker broker = new Broker();
        try (var first = instance(broker); var second = instance(broker)) {
            var a = first.getBean(UserProfileClientFixed.class);
            var b = second.getBean(UserProfileClientFixed.class);
            List<CompletableFuture<UserProfileDTO>> pending = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Trace.set("profile-" + i);
                pending.add((i % 2 == 0 ? a : b).requestUserProfile("patient-" + i));
            }
            broker.replyInReverseOrder();
            for (int i = 0; i < pending.size(); i++) {
                assertEquals("patient-" + i, pending.get(i).get(200, TimeUnit.MILLISECONDS).getBasicInfo().get("customerId"));
            }
            assertEquals(0, a.getPendingRequestCount());
            assertEquals(0, b.getPendingRequestCount());
        }
    }

    @Test
    void memoryRepliesCompleteOnlyTheOriginatingInstanceEvenWhenDeliveredOutOfOrder() throws Exception {
        Broker broker = new Broker();
        try (var first = instance(broker); var second = instance(broker)) {
            var a = first.getBean(VectorMemoryClientFixed.class);
            var b = second.getBean(VectorMemoryClientFixed.class);
            List<CompletableFuture<List<MemoryItemDTO>>> pending = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Trace.set("memory-" + i);
                pending.add((i % 2 == 0 ? a : b).requestMemories("group", "patient-" + i, "query", 1));
            }
            broker.replyInReverseOrder();
            for (int i = 0; i < pending.size(); i++) {
                assertEquals("patient-" + i, pending.get(i).get(200, TimeUnit.MILLISECONDS).get(0).getContent());
            }
            assertEquals(0, a.getPendingRequestCount());
            assertEquals(0, b.getPendingRequestCount());
        }
    }

    private AnnotationConfigApplicationContext instance(Broker broker) {
        var context = new AnnotationConfigApplicationContext();
        var config = new RabbitMQConfig();
        context.registerBean("userProfileReplyQueue", Queue.class, config::userProfileReplyQueue);
        context.registerBean("vectorMemoryReplyQueue", Queue.class, config::vectorMemoryReplyQueue);
        context.registerBean(RabbitTemplate.class, broker::template);
        context.register(UserProfileClientFixed.class, VectorMemoryClientFixed.class);
        context.refresh();
        broker.listen(context, UserProfileClientFixed.class);
        broker.listen(context, VectorMemoryClientFixed.class);
        return context;
    }

    /** 只模拟按 replyTo 路由及同队列竞争消费；请求、监听声明、Future 匹配均使用真实代码。 */
    private static class Broker {
        private final Map<String, List<BiConsumer<Object, Message>>> consumers = new HashMap<>();
        private final List<Runnable> replies = new ArrayList<>();

        RabbitTemplate template() {
            RabbitTemplate template = mock(RabbitTemplate.class);
            doAnswer(invocation -> {
                Object request = invocation.getArgument(1);
                MessagePostProcessor postProcessor = invocation.getArgument(2);
                Message sent = postProcessor.postProcessMessage(new Message(new byte[0]));
                Object response;
                if (request instanceof UserProfileRequestMessage profileRequest) {
                    var profile = new UserProfileDTO();
                    profile.setBasicInfo(Map.of("customerId", profileRequest.getCustomerId()));
                    var result = new UserProfileResponseMessage();
                    result.setSuccess(true);
                    result.setData(profile);
                    response = result;
                } else {
                    var memoryRequest = (MemoryRequestMessage) request;
                    var memory = new MemoryItemDTO();
                    memory.setContent(memoryRequest.getCustomerId());
                    var result = new MemoryResponseMessage();
                    result.setSuccess(true);
                    result.setData(List.of(memory));
                    response = result;
                }
                replies.add(() -> {
                    var listeners = consumers.get(sent.getMessageProperties().getReplyTo());
                    assertNotNull(listeners, "replyTo 必须对应本实例实际监听的队列");
                    // 固定共享队列会把响应送到第二个实例，稳定复现原始丢响应问题。
                    listeners.get(listeners.size() - 1).accept(response, sent);
                });
                return null;
            }).when(template).convertAndSend(anyString(), any(Object.class), any(MessagePostProcessor.class));
            return template;
        }

        void listen(AnnotationConfigApplicationContext context, Class<?> type) {
            Object client = context.getBean(type);
            for (Method method : type.getDeclaredMethods()) {
                RabbitListener annotation = method.getAnnotation(RabbitListener.class);
                if (annotation == null) {
                    continue;
                }
                String queue = (String) context.getBeanFactory().getBeanExpressionResolver().evaluate(
                        annotation.queues()[0], new BeanExpressionContext(context.getBeanFactory(), null));
                consumers.computeIfAbsent(queue, ignored -> new ArrayList<>()).add((response, message) -> {
                    try {
                        method.invoke(client, response, message);
                    } catch (ReflectiveOperationException ex) {
                        throw new AssertionError(ex);
                    }
                });
            }
        }

        void replyInReverseOrder() {
            Collections.reverse(replies);
            replies.forEach(Runnable::run);
        }
    }
}
