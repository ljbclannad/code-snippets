package com.lx.governance.aichat.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.config.RabbitMQConfig;
import com.lx.governance.common.trace.Trace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 Broker 和生产 RPC 客户端，所有请求及回复队列均为临时独占队列。 */
@EnabledIfEnvironmentVariable(named = "AI_TEST_RABBITMQ_HOST", matches = ".+")
class RpcReplyBrokerIntegrationTest {
    @Test
    void twoInstancesReceiveTheirOwnRepliesAndOneCanStopIndependently() throws Exception {
        CachingConnectionFactory server = connectionFactory();
        var requests = new RequestQueues(new AnonymousQueue(), new AnonymousQueue());
        var admin = new RabbitAdmin(server);
        var responder = new SimpleMessageListenerContainer(server);
        try {
            admin.declareQueue(requests.profile());
            admin.declareQueue(requests.memory());
            var template = new RabbitTemplate(server);
            var mapper = new ObjectMapper();
            responder.setQueueNames(requests.profile().getName(), requests.memory().getName());
            responder.setMessageListener((MessageListener) request -> {
                try {
                    var payload = mapper.readTree(request.getBody());
                    String customer = payload.path("customerId").asText();
                    Object data = payload.has("query")
                            ? List.of(Map.of("content", customer))
                            : Map.of("basicInfo", Map.of("customerId", customer));
                    var properties = new MessageProperties();
                    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                    properties.setCorrelationId(request.getMessageProperties().getCorrelationId());
                    template.send("", request.getMessageProperties().getReplyTo(), new Message(
                            mapper.writeValueAsBytes(Map.of("success", true, "data", data)), properties));
                } catch (Exception ex) {
                    throw new IllegalStateException("测试响应构造失败", ex);
                }
            });
            responder.start();
            try (var first = instance(requests); var second = instance(requests)) {
                String firstProfile = first.getBean("userProfileReplyQueue", Queue.class).getName();
                String secondProfile = second.getBean("userProfileReplyQueue", Queue.class).getName();
                assertNotEquals(firstProfile, secondProfile);
                List<CompletableFuture<Void>> checks = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    checks.add(checkReplies(i % 2 == 0 ? first : second, "synthetic-" + i));
                }
                CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).get(4, TimeUnit.SECONDS);
                assertEquals(0, first.getBean(UserProfileClientFixed.class).getPendingRequestCount());
                assertEquals(0, first.getBean(VectorMemoryClientFixed.class).getPendingRequestCount());
                assertEquals(0, second.getBean(UserProfileClientFixed.class).getPendingRequestCount());
                assertEquals(0, second.getBean(VectorMemoryClientFixed.class).getPendingRequestCount());

                // 关闭一个独占队列所属连接，不应影响另一个实例。
                first.close();
                checkReplies(second, "surviving-instance").get(4, TimeUnit.SECONDS);
                assertNull(admin.getQueueProperties(firstProfile));
                assertNotNull(second.getBean(RabbitAdmin.class).getQueueProperties(secondProfile));
            }
        } finally {
            responder.stop();
            server.destroy();
            Trace.clear();
        }
    }

    private CompletableFuture<Void> checkReplies(AnnotationConfigApplicationContext context, String customer) {
        Trace.set("profile-" + customer);
        var profile = context.getBean(UserProfileClientFixed.class).requestUserProfile(customer)
                .thenAccept(result -> assertEquals(customer, result.getBasicInfo().get("customerId")));
        Trace.set("memory-" + customer);
        var memory = context.getBean(VectorMemoryClientFixed.class).requestMemories("synthetic-group", customer, "query", 1)
                .thenAccept(result -> assertEquals(customer, result.get(0).getContent()));
        return CompletableFuture.allOf(profile, memory);
    }

    private AnnotationConfigApplicationContext instance(RequestQueues requests) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(RequestQueues.class, () -> requests);
        context.register(ClientConfig.class);
        context.refresh();
        return context;
    }

    private static CachingConnectionFactory connectionFactory() {
        var factory = new CachingConnectionFactory(System.getenv("AI_TEST_RABBITMQ_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("AI_TEST_RABBITMQ_PORT", "5672")));
        factory.setUsername(System.getenv().getOrDefault("AI_TEST_RABBITMQ_USERNAME", "guest"));
        factory.setPassword(System.getenv().getOrDefault("AI_TEST_RABBITMQ_PASSWORD", "guest"));
        factory.setVirtualHost(System.getenv().getOrDefault("AI_TEST_RABBITMQ_VHOST", "/"));
        return factory;
    }

    record RequestQueues(Queue profile, Queue memory) {}

    @Configuration(proxyBeanMethods = false)
    @EnableRabbit
    @Import({UserProfileClientFixed.class, VectorMemoryClientFixed.class})
    static class ClientConfig {
        private final RabbitMQConfig production = new RabbitMQConfig();

        @Bean(destroyMethod = "destroy")
        CachingConnectionFactory clientConnectionFactory() {
            return connectionFactory();
        }

        @Bean
        RabbitAdmin rabbitAdmin(CachingConnectionFactory connection) {
            return new RabbitAdmin(connection);
        }

        @Bean
        Queue userProfileReplyQueue() {
            return production.userProfileReplyQueue();
        }

        @Bean
        Queue vectorMemoryReplyQueue() {
            return production.vectorMemoryReplyQueue();
        }

        @Bean
        SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(CachingConnectionFactory connection) {
            return production.rabbitListenerContainerFactory(connection, production.jsonMessageConverter());
        }

        @Bean
        RabbitTemplate rabbitTemplate(CachingConnectionFactory connection, RequestQueues requests) {
            // 只替换请求目的队列，保留真实发送、replyTo、序列化及监听链路，避免消费业务队列。
            var template = new RabbitTemplate(connection) {
                @Override
                public void convertAndSend(String routingKey, Object payload, MessagePostProcessor postProcessor) {
                    String isolated = switch (routingKey) {
                        case "user.profile.request" -> requests.profile().getName();
                        case "vector.memory.request" -> requests.memory().getName();
                        default -> throw new AssertionError("非预期的测试请求队列：" + routingKey);
                    };
                    super.convertAndSend(isolated, payload, postProcessor);
                }
            };
            template.setMessageConverter(production.jsonMessageConverter());
            return template;
        }
    }
}
