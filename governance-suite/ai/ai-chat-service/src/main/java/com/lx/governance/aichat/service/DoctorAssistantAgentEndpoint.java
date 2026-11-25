package com.lx.governance.aichat.service;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class DoctorAssistantAgentEndpoint {

    private final LoadBalancerClient loadBalancer;
    private final String serviceId;
    private final URI explicitBaseUri;

    public DoctorAssistantAgentEndpoint(LoadBalancerClient loadBalancer,
            @Value("${ai-chat.agent.service-id:doctor-assistant-agent}") String serviceId,
            @Value("${ai-chat.agent.base-url:}") String baseUrl) {
        this.loadBalancer = loadBalancer;
        this.serviceId = serviceId;
        this.explicitBaseUri = baseUrl.isBlank() ? null : URI.create(baseUrl);
    }

    public URI resolve(String path) {
        URI baseUri = explicitBaseUri != null ? explicitBaseUri : discoverBaseUri();
        return UriComponentsBuilder.fromUri(baseUri).path(path).build().toUri();
    }

    private URI discoverBaseUri() {
        ServiceInstance instance;
        try {
            instance = loadBalancer.choose(serviceId);
        } catch (RuntimeException ex) {
            throw new RestClientException("查询医生助手实例失败：" + serviceId, ex);
        }
        if (instance == null) {
            throw new RestClientException("Nacos 中没有可用的医生助手实例：" + serviceId);
        }
        return instance.getUri();
    }
}
