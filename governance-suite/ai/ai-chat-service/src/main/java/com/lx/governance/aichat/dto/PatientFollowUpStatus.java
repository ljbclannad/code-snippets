package com.lx.governance.aichat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record PatientFollowUpStatus(String status, boolean delivered,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) String turnKey) {
    public static final String UNCONFIRMED = "UNCONFIRMED";
}
