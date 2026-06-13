package com.lx.governance.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BasicInfoDTO {
    private String customerId;
    private String name;
    private Integer age;
    private String gender;
    private String phone;
    private String email;
}
