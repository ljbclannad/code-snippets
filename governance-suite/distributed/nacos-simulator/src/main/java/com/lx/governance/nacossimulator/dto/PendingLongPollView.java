package com.lx.governance.nacossimulator.dto;

import com.lx.governance.nacossimulator.model.ConfigWatchKey;
import java.util.List;

public record PendingLongPollView(
        String clientId,
        List<ConfigWatchKey> watchKeys) {
}
