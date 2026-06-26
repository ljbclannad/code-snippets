package com.lx.governance.nacossimulator.model;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ConfigItem {
    String dataId;
    String group;
    String content;
    long version;
    String md5;
    Instant updatedAt;
}
