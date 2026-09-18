package com.lx.governance.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ProductSearchConfiguration {
    @Bean
    ElasticsearchProductIndex productIndex(ObjectMapper mapper,
            @Value("${product-search.elasticsearch.url}") String url,
            @Value("${product-search.elasticsearch.index}") String name,
            @Value("${product-search.elasticsearch.username:}") String username,
            @Value("${product-search.elasticsearch.password:}") String password) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(1500);
        requests.setReadTimeout(3000);
        RestClient.Builder builder = RestClient.builder().baseUrl(url).requestFactory(requests);
        if (!username.isBlank()) builder.defaultHeaders(headers -> headers.setBasicAuth(username, password));
        ElasticsearchProductIndex index = new ElasticsearchProductIndex(builder.build(), mapper, name);
        // 在消费者建立前完成映射，避免首条消息触发 ES 自动创建错误索引。
        index.initialize();
        return index;
    }

    @Bean
    TopicExchange productEventsExchange() { return new TopicExchange("biz.events", true, false); }

    @Bean
    DirectExchange productSearchDeadExchange() { return new DirectExchange("biz.product.search.dead", true, false); }

    @Bean
    Queue productSearchQueue() {
        return QueueBuilder.durable("q.product.search").deadLetterExchange("biz.product.search.dead")
                .deadLetterRoutingKey("product.changed").build();
    }

    @Bean
    Queue productSearchDeadQueue() { return QueueBuilder.durable("q.product.search.dead").build(); }

    @Bean
    Binding productSearchBinding() {
        return BindingBuilder.bind(productSearchQueue()).to(productEventsExchange()).with("product.changed");
    }

    @Bean
    Binding productSearchDeadBinding() {
        return BindingBuilder.bind(productSearchDeadQueue()).to(productSearchDeadExchange()).with("product.changed");
    }
}
