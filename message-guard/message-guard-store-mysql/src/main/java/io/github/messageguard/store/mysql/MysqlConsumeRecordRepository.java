package io.github.messageguard.store.mysql;

import io.github.messageguard.core.model.ConsumeRecord;
import io.github.messageguard.core.repository.ConsumeRecordRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class MysqlConsumeRecordRepository implements ConsumeRecordRepository {

    private final Map<String, ConsumeRecord> storage = new ConcurrentHashMap<>();

    @Override
    public Optional<ConsumeRecord> findByUniqueKey(String consumer, String group, String idempotentKey) {
        return Optional.ofNullable(storage.get(key(consumer, group, idempotentKey)));
    }

    @Override
    public ConsumeRecord save(ConsumeRecord record) {
        storage.put(key(record.consumer(), record.group(), record.idempotentKey()), record);
        return record;
    }

    private String key(String consumer, String group, String idempotentKey) {
        return consumer + ":" + group + ":" + idempotentKey;
    }
}
