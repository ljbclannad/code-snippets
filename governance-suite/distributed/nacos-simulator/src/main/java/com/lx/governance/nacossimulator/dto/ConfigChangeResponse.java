package com.lx.governance.nacossimulator.dto;

import com.lx.governance.nacossimulator.model.ConfigItem;
import java.util.List;

public record ConfigChangeResponse(boolean changed, List<ConfigItem> items) {
}
