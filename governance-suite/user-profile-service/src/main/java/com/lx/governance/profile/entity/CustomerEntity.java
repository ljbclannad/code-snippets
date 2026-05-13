package com.lx.governance.profile.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CustomerEntity {
    private String customerId;
    private String name;
    private Integer age;
    private String gender;
    private String phone;
    private String email;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
