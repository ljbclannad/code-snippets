package com.lx.governance.aichat.context;

import java.util.List;

import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PromptBuildInput {
    UserProfileDTO profile;
    SessionContext sessionContext;
    List<MemoryItemDTO> memories;
    LayeredMemories layeredMemories;
    String customerId;
    String conversationId;
    String userQuestion;

    public boolean hasLayeredMemories() {
        return layeredMemories != null;
    }
}
