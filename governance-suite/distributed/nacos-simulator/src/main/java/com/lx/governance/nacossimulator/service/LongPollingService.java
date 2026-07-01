package com.lx.governance.nacossimulator.service;

import com.lx.governance.nacossimulator.dto.ConfigChangeResponse;
import com.lx.governance.nacossimulator.dto.ConfigProbe;
import com.lx.governance.nacossimulator.model.ConfigItem;
import com.lx.governance.nacossimulator.model.ConfigWatchKey;
import com.lx.governance.nacossimulator.model.PendingConfigPoll;
import com.lx.governance.nacossimulator.store.InMemoryLongPollStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LongPollingService {

    private final ConfigService configService;
    private final InMemoryLongPollStore store;
    private final long timeoutMs;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    public LongPollingService(
            ConfigService configService,
            InMemoryLongPollStore store,
            @Value("${simulator.config.long-poll-timeout-ms:30000}") long timeoutMs) {
        this(configService, store, timeoutMs, true);
    }

    public LongPollingService(ConfigService configService, long timeoutMs) {
        this(configService, new InMemoryLongPollStore(), timeoutMs, false);
    }

    private LongPollingService(ConfigService configService, InMemoryLongPollStore store,
            long timeoutMs, boolean wireBackReference) {
        this.configService = configService;
        this.store = store;
        this.timeoutMs = timeoutMs;
        if (wireBackReference) {
            this.configService.setLongPollingService(this);
        }
    }

    public ConfigChangeResponse listen(String clientId, List<ConfigProbe> probes) {
        return listenAsync(clientId, probes).join();
    }

    public CompletableFuture<ConfigChangeResponse> listenAsync(String clientId, List<ConfigProbe> probes) {
        List<ConfigItem> changedItems = changedItems(probes);
        if (!changedItems.isEmpty()) {
            return CompletableFuture.completedFuture(new ConfigChangeResponse(true, changedItems));
        }

        CompletableFuture<ConfigChangeResponse> future = new CompletableFuture<>();
        List<ConfigWatchKey> watchKeys = probes.stream()
                .map(probe -> new ConfigWatchKey(probe.dataId(), probe.group()))
                .toList();
        Map<ConfigWatchKey, String> knownMd5 = probes.stream()
                .collect(Collectors.toMap(
                        probe -> new ConfigWatchKey(probe.dataId(), probe.group()),
                        ConfigProbe::md5));
        PendingConfigPoll poll = PendingConfigPoll.builder()
                .clientId(clientId)
                .watchKeys(watchKeys)
                .knownMd5Map(knownMd5)
                .future(future)
                .build();
        store.add(poll);
        future.whenComplete((ignored, throwable) -> store.remove(poll));
        scheduler.schedule(() -> future.complete(new ConfigChangeResponse(false, List.of())),
                timeoutMs, TimeUnit.MILLISECONDS);
        return future;
    }

    public void completeChanged(ConfigItem changedItem) {
        ConfigWatchKey changedKey = new ConfigWatchKey(changedItem.getDataId(), changedItem.getGroup());
        for (PendingConfigPoll poll : store.list()) {
            if (!poll.getWatchKeys().contains(changedKey)) {
                continue;
            }
            String knownMd5 = poll.getKnownMd5Map().get(changedKey);
            if (changedItem.getMd5().equals(knownMd5)) {
                continue;
            }
            poll.getFuture().complete(new ConfigChangeResponse(true, List.of(changedItem)));
        }
    }

    private List<ConfigItem> changedItems(List<ConfigProbe> probes) {
        List<ConfigItem> changed = new ArrayList<>();
        for (ConfigProbe probe : probes) {
            configService.get(probe.dataId(), probe.group())
                    .filter(item -> !item.getMd5().equals(probe.md5()))
                    .ifPresent(changed::add);
        }
        return changed;
    }
}
