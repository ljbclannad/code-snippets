package com.lx.governance.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalMemoryDTO {
    private String type;
    private String key;
    private String content;
    private Boolean verified;
}
