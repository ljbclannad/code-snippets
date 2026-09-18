package com.lx.governance.aichat.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** 目标由服务配置固定，样本不能指定任意 HTTP 地址。 */
@Component
public class EvaluationCaptureTransport {
    private final EvaluationCaptureProperties properties;
    private final ObjectMapper mapper;
    private final RestTemplate rest;

    public EvaluationCaptureTransport(EvaluationCaptureProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(100, properties.getTimeoutMs()));
        factory.setReadTimeout(Math.max(100, properties.getTimeoutMs()));
        rest = new RestTemplate(factory);
    }

    public JsonNode replay(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("非法运行关联标识");
        }
        URI uri = UriComponentsBuilder.fromUriString(properties.getAgentBaseUrl())
                .pathSegment("api", "runs", runId, "replay").build().encode().toUri();
        return rest.execute(uri, HttpMethod.GET, null, response -> {
            byte[] bytes = response.getBody().readNBytes(properties.getMaxPayloadBytes() + 1);
            if (bytes.length > properties.getMaxPayloadBytes()) { throw new IOException("回放超过采集大小限制"); }
            return mapper.readTree(bytes);
        });
    }

    public void publish(String batchId, JsonNode observation) {
        byte[] body;
        try { body = mapper.writeValueAsBytes(observation); }
        catch (IOException e) { throw new RestClientException("无法序列化评测观测", e); }
        if (body.length > properties.getMaxPayloadBytes()) { throw new RestClientException("评测观测超过大小限制"); }
        URI uri = UriComponentsBuilder.fromUriString(properties.getBaseUrl())
                .pathSegment("api", "evaluation", "batches", batchId, "observations").build().encode().toUri();
        rest.execute(uri, HttpMethod.POST, request -> {
            request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            request.getBody().write(body);
        }, response -> null);
    }
}
