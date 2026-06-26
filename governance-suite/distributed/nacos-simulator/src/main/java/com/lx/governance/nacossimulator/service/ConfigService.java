package com.lx.governance.nacossimulator.service;

import com.lx.governance.nacossimulator.model.ConfigItem;
import com.lx.governance.nacossimulator.model.ConfigWatchKey;
import com.lx.governance.nacossimulator.store.InMemoryConfigStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ConfigService {

    private final InMemoryConfigStore store;
    private LongPollingService longPollingService;

    @Autowired
    public ConfigService(InMemoryConfigStore store) {
        this.store = store;
    }

    public ConfigService() {
        this(new InMemoryConfigStore());
    }

    public ConfigItem publish(String dataId, String group, String content) {
        ConfigWatchKey key = new ConfigWatchKey(dataId, group);
        long nextVersion = store.get(key)
                .map(ConfigItem::getVersion)
                .orElse(0L) + 1L;
        ConfigItem item = ConfigItem.builder()
                .dataId(dataId)
                .group(group)
                .content(content)
                .version(nextVersion)
                .md5(md5Hex(content))
                .updatedAt(Instant.now())
                .build();
        ConfigItem stored = store.put(key, item);
        if (longPollingService != null) {
            longPollingService.completeChanged(stored);
        }
        return stored;
    }

    public Optional<ConfigItem> get(String dataId, String group) {
        return store.get(new ConfigWatchKey(dataId, group));
    }

    public void setLongPollingService(LongPollingService longPollingService) {
        this.longPollingService = longPollingService;
    }

    private String md5Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 algorithm unavailable", ex);
        }
    }
}
