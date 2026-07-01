package io.github.messageguard.core.repository;

import io.github.messageguard.core.model.ConsumeRecord;

import java.util.Optional;

public interface ConsumeRecordRepository {

    Optional<ConsumeRecord> findByUniqueKey(String consumer, String group, String idempotentKey);

    ConsumeRecord save(ConsumeRecord record);
}
