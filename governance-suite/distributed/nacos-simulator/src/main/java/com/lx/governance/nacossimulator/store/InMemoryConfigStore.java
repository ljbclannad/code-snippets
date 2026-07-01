package com.lx.governance.nacossimulator.store;

import com.lx.governance.nacossimulator.model.ConfigItem;
import com.lx.governance.nacossimulator.model.ConfigWatchKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryConfigStore {

    private final ConcurrentMap<ConfigWatchKey, ConfigItem> configs = new ConcurrentHashMap<>();

    public Optional<ConfigItem> get(ConfigWatchKey key) {
        return Optional.ofNullable(configs.get(key));
    }

    public ConfigItem put(ConfigWatchKey key, ConfigItem item) {
        configs.put(key, item);
        return item;
    }

    public List<ConfigItem> listAll() {
        return new ArrayList<>(configs.values());
    }
}
