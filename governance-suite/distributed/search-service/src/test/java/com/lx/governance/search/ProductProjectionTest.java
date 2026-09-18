package com.lx.governance.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class ProductProjectionTest {
    @Test
    void malformedProductEventsCannotBecomeSearchableDocuments() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://es");
        MockRestServiceServer es = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ProductEventListener listener = new ProductEventListener(mapper,
                new ElasticsearchProductIndex(builder.build(), mapper, "products-test"), new SimpleMeterRegistry());
        for (String body : new String[]{"not-json", "null", "{}", "{\"eventId\":\"e1\",\"eventType\":\"product.changed\",\"product\":{}}"}) {
            Message message = new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
            assertThatThrownBy(() -> listener.consume(message)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
        es.verify();
    }

    @Test
    void oldOrRepeatedEventsAreIgnoredButMappingFailuresRemainRetryable() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://es");
        MockRestServiceServer es = MockRestServiceServer.bindTo(builder).build();
        String url = "http://es/products-test/_doc/shoe-1?version=1&version_type=external";
        es.expect(requestTo(url)).andExpect(method(HttpMethod.PUT)).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"type\":\"version_conflict_engine_exception\"}}"));
        es.expect(requestTo(url)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"type\":\"mapper_parsing_exception\"}}"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ElasticsearchProductIndex index = new ElasticsearchProductIndex(builder.build(), mapper, "products-test");
        ProductDocument product = mapper.readValue("""
                {"productId":"shoe-1","title":"跑步鞋","categoryId":"shoes","brandId":"brand-a",
                 "priceCents":10000,"status":"OFF_SALE","version":1,"updatedAt":"2026-09-18T00:00:00Z"}
                """, ProductDocument.class);
        assertThat(index.project(product)).isFalse();
        assertThatThrownBy(() -> index.project(product)).isInstanceOf(SearchUnavailableException.class);
        es.verify();
    }
}
