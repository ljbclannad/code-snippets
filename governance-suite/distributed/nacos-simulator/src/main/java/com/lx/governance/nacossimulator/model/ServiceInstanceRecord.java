package com.lx.governance.nacossimulator.model;

import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class ServiceInstanceRecord {
    String serviceName;
    String instanceId;
    String ip;
    int port;
    Map<String, String> metadata;
    Instant registeredAt;
    Instant lastHeartbeatAt;
    long ttlMillis;
    boolean healthy;
}
