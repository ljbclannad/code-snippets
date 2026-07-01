package com.lx.governance.nacossimulator.model;

import com.lx.governance.nacossimulator.dto.ConfigChangeResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PendingConfigPoll {
    String clientId;
    List<ConfigWatchKey> watchKeys;
    Map<ConfigWatchKey, String> knownMd5Map;
    CompletableFuture<ConfigChangeResponse> future;
}
